package com.autocare.server.ai;

import com.autocare.ai.booking.*;
import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.user.UserMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class BookingDraftService {

  private final BookingDraftParser parser;
  private final BookingAiProperties properties;
  private final UserMapper users;
  private final AiCallMapper calls;
  private final ThreadPoolExecutor executor;
  private final Map<Long, Window> quota = new HashMap<>();

  private record Window(long minute, int calls) {}

  public BookingDraftService(
    BookingDraftParser parser,
    BookingAiProperties properties,
    UserMapper users,
    AiCallMapper calls
  ) {
    this.parser = parser;
    this.properties = properties;
    this.users = users;
    this.calls = calls;
    executor = new ThreadPoolExecutor(
      properties.concurrency(),
      properties.concurrency(),
      0,
      TimeUnit.MILLISECONDS,
      new SynchronousQueue<>(),
      Thread.ofPlatform().name("autocare-draft-", 0).factory(),
      new ThreadPoolExecutor.AbortPolicy()
    );
  }

  public Map<String, Object> parse(long actor, JsonNode body, String key) {
    long deadline = System.nanoTime() + properties.timeout().toNanos();
    requireOwner(actor);
    if (key != null) throw ApiException.invalid(
      FieldErrors.notSupported("/headers/Idempotency-Key", "该操作不接受幂等键")
    );
    if (body == null || !body.isObject()) throw ApiException.invalid(
      FieldErrors.invalidFormat("/", "必须为对象")
    );
    for (String field : body.propertyNames())
      if (
        !Set.of("text", "timeZone").contains(field)
      ) throw ApiException.invalid(
        FieldErrors.unknownField("/" + field, "未知字段")
      );
    if (!body.has("text")) throw ApiException.invalid(
      FieldErrors.required("/text")
    );
    if (body.path("text").isNull()) throw ApiException.invalid(
      FieldErrors.nullNotAllowed("/text")
    );
    if (!body.path("text").isString()) throw ApiException.invalid(
      FieldErrors.invalidFormat("/text", "必须为字符串")
    );
    String text = body.path("text").asString().strip();
    int len = text.codePointCount(0, text.length());
    if (len < 1 || len > 4000) throw ApiException.invalid(
      FieldErrors.outOfRange("/text", "去除首尾空白后必须为1至4000字符")
    );
    if (
      body.has("timeZone") &&
      (!body.path("timeZone").isString() ||
        !"Asia/Shanghai".equals(body.path("timeZone").asString()))
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/timeZone", "仅支持Asia/Shanghai")
    );
    reserveQuota(actor);
    long start = System.nanoTime();
    Instant now = Instant.now();
    AiCallRow row = new AiCallRow();
    row.actorId = actor;
    row.modelId = properties.model();
    row.inputHash = sha(text + "\nAsia/Shanghai");
    Future<BookingDraftParser.Parsed> future;
    try {
      future = executor.submit(() -> parser.parse(text, now, "Asia/Shanghai"));
    } catch (RejectedExecutionException e) {
      throw ApiException.of(ErrorCode.AI_QUEUE_FULL);
    }
    BookingDraftParser.Parsed parsed;
    try {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new TimeoutException();
      parsed = future.get(remaining, TimeUnit.NANOSECONDS);
      DraftValidator.validate(parsed.draft(), text, now);
      if (
        parsed.modelId() == null ||
        parsed.modelId().isBlank() ||
        parsed.modelId().length() > 64
      ) throw new DraftValidationException();
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      record(row, "FAILED", start);
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    } catch (TimeoutException e) {
      future.cancel(true);
      record(row, "TIMEOUT", start);
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    } catch (ExecutionException e) {
      record(
        row,
        validation(e.getCause()) ? "VALIDATION_FAILED" : "FAILED",
        start
      );
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    } catch (DraftValidationException e) {
      record(row, "VALIDATION_FAILED", start);
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    }
    // 模型运行期间身份可能被禁用；输出返回前重新复核。
    try {
      requireOwner(actor);
    } catch (ApiException e) {
      record(row, "FAILED", start);
      throw e;
    }
    row.modelId = parsed.modelId();
    if (
      validTokens(parsed.promptTokens()) &&
      validTokens(parsed.completionTokens()) &&
      validTokens(parsed.totalTokens())
    ) {
      row.promptTokens = parsed.promptTokens();
      row.completionTokens = parsed.completionTokens();
      row.totalTokens = parsed.totalTokens();
    }
    record(row, "SUCCEEDED", start);
    BookingDraft d = parsed.draft();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("aiParseId", String.valueOf(row.id));
    data.put("vehicleHints", d.vehicleHints());
    data.put("symptoms", d.symptoms());
    data.put("preferredTimeText", d.preferredTimeText());
    data.put("preferredTimeRange", d.preferredTimeRange());
    data.put("missingFields", d.missingFields());
    data.put("evidenceSpans", d.evidenceSpans());
    data.put("limitations", d.limitations());
    data.put("modelId", row.modelId);
    data.put("promptVersion", "booking-draft-v1");
    return data;
  }

  public void requireOwner(long actor) {
    var user = users.findById(actor);
    if (user == null || !user.isEnabled()) throw ApiException.of(
      ErrorCode.UNAUTHENTICATED
    );
    if (user.getRole() != Role.OWNER) throw ApiException.of(
      ErrorCode.FORBIDDEN
    );
  }

  private synchronized void reserveQuota(long actor) {
    long minute = System.currentTimeMillis() / 60000;
    quota.entrySet().removeIf(e -> e.getValue().minute() != minute);
    var w = quota.get(actor);
    int n = w == null ? 0 : w.calls();
    if (n >= properties.perMinute()) throw ApiException.of(
      ErrorCode.RATE_LIMITED
    );
    if (quota.size() >= 10000 && w == null) throw ApiException.of(
      ErrorCode.AI_QUEUE_FULL
    );
    quota.put(actor, new Window(minute, n + 1));
  }

  private void record(AiCallRow row, String outcome, long start) {
    row.outcome = outcome;
    row.errorCode = outcome.equals("SUCCEEDED") ? null : "AI_UNAVAILABLE";
    row.latencyMs = (int) Math.min(
      600000,
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    );
    calls.insert(row);
  }

  private static boolean validTokens(Integer v) {
    return v != null && v >= 0 && v <= 1000000;
  }

  private static boolean validation(Throwable t) {
    while (t != null) {
      if (t instanceof DraftValidationException) return true;
      t = t.getCause();
    }
    return false;
  }

  private static String sha(String s) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          s.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
