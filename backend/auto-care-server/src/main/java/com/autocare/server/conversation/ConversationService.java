package com.autocare.server.conversation;

import com.autocare.ai.conversation.ConversationAssistant;
import com.autocare.ai.knowledge.KnowledgeFiles;
import com.autocare.common.error.*;
import com.autocare.server.ai.BookingAiProperties;
import com.autocare.server.command.*;
import com.autocare.server.knowledge.KnowledgeInputs;
import com.autocare.server.web.UtcTimes;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.*;

@Service
public class ConversationService {

  private final ConversationMapper mapper;
  private final ConversationAssistant assistant;
  private final ConversationTools tools;
  private final CommandExecutor commands;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final BookingAiProperties p;
  private final ThreadPoolExecutor executor;

  public ConversationService(
    ConversationMapper mapper,
    ConversationAssistant assistant,
    ConversationTools tools,
    CommandExecutor commands,
    ObjectMapper json,
    PlatformTransactionManager manager,
    BookingAiProperties p
  ) {
    this.mapper = mapper;
    this.assistant = assistant;
    this.tools = tools;
    this.commands = commands;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.p = p;
    executor = new ThreadPoolExecutor(
      p.concurrency(),
      p.concurrency(),
      0,
      TimeUnit.MILLISECONDS,
      new SynchronousQueue<>(),
      Thread.ofPlatform().name("autocare-conversation-", 0).factory(),
      new ThreadPoolExecutor.AbortPolicy()
    );
  }

  public void require(long actor) {
    tools.role(actor);
  }

  public CommandResult create(
    long actor,
    JsonNode body,
    String key,
    String requestId
  ) {
    require(actor);
    if (body == null || !body.isObject() || !body.isEmpty()) throw invalid(
      "/",
      "会话创建只接受空对象"
    );
    return commands.execute(
      CommandSpec.of("createAiConversation"),
      key,
      Map.of(),
      requestId,
      () -> {
        lockActor(actor);
        if (mapper.count(actor) >= 30) throw ApiException.of(
          ErrorCode.RATE_LIMITED
        );
        var row = new ConversationMapper.Row();
        row.publicId = UUID.randomUUID().toString();
        row.actorId = actor;
        row.facts = json.writeValueAsString(ConversationFacts.empty());
        mapper.create(row);
        return CommandOutcome.created(
          "/api/v1/ai/conversations/" + row.publicId,
          view(mapper.find(row.publicId, actor)),
          row.id
        );
      },
      (who, id) -> id != null && mapper.visible(id, who) == 1
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(
    long actor,
    MultiValueMap<String, String> query
  ) {
    require(actor);
    KnowledgeInputs.query(query, Set.of("page", "pageSize"));
    int page = KnowledgeInputs.page(query.getFirst("page"), "page", 1, 10000),
      size = KnowledgeInputs.page(
        query.getFirst("pageSize"),
        "pageSize",
        20,
        100
      );
    return Map.of(
      "items",
      mapper
        .list(actor, size, (page - 1) * size)
        .stream()
        .map(this::view)
        .toList(),
      "page",
      page,
      "pageSize",
      size,
      "total",
      mapper.count(actor)
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> get(long actor, String id) {
    require(actor);
    var row = found(mapper.find(publicId(id), actor));
    var data = new LinkedHashMap<>(view(row));
    data.put(
      "turns",
      mapper
        .history(row.id)
        .stream()
        .map(t ->
          Map.of(
            "message",
            t.messageText,
            "reply",
            json.readTree(t.responseJson).path("data").path("reply"),
            "createdAt",
            UtcTimes.format(t.createdAt)
          )
        )
        .toList()
    );
    return data;
  }

  public void delete(long actor, String id) {
    require(actor);
    String publicId = publicId(id);
    tx.executeWithoutResult(s -> {
      lockActor(actor);
      var row = found(mapper.lock(publicId, actor));
      assistant.clear(publicId);
      mapper.deleteTurns(row.id);
      mapper.delete(row.id);
    });
  }

  private record Claim(
    ConversationMapper.Row row,
    ConversationMapper.Turn turn,
    String token,
    CommandResult replay
  ) {}

  public CommandResult send(
    long actor,
    String id,
    JsonNode body,
    String rawKey,
    String requestId
  ) {
    long deadline = System.nanoTime() + p.timeout().toNanos();
    require(actor);
    String conversation = publicId(id);
    String key = IdempotencyKey.parse(rawKey).value();
    if (
      body == null ||
      !body.isObject() ||
      !body.propertyNames().equals(Set.of("message", "expectedRevision")) ||
      !body.path("message").isString() ||
      !body.path("expectedRevision").isString()
    ) throw invalid("/", "需要message和expectedRevision字符串");
    String message = KnowledgeInputs.text(
      body.path("message").asString(),
      "message",
      2000,
      true
    );
    String rev = body.path("expectedRevision").asString();
    if (!rev.matches("0|[1-9][0-9]{0,8}")) throw invalid(
      "/expectedRevision",
      "版本格式不合法"
    );
    String hash = KnowledgeFiles.hash(rev + "\n" + message);
    Claim claim = tx.execute(s ->
      claim(actor, conversation, key, hash, message, Integer.parseInt(rev))
    );
    if (claim.replay() != null) return claim.replay();
    Future<ConversationAssistant.Prepared> future = null;
    try {
      future = executor.submit(() ->
        assistant.prepare(
          conversation,
          claim.row().facts,
          message,
          tools.forActor(actor)
        )
      );
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new TimeoutException();
      var prepared = future.get(remaining, TimeUnit.NANOSECONDS);
      var facts = json
        .readValue(claim.row().facts, ConversationFacts.class)
        .apply(actor, message, prepared, tools);
      return tx.execute(s -> {
        lockActor(actor);
        var current = found(mapper.lock(conversation, actor));
        if (
          !Objects.equals(current.claimToken, claim.token()) ||
          current.leaseUntil == null ||
          !current.leaseUntil.isAfter(mapper.now())
        ) throw ApiException.of(ErrorCode.RESOURCE_VERSION_CONFLICT);
        var data = new LinkedHashMap<String, Object>();
        data.put("conversationId", conversation);
        data.put("revision", "" + (current.revision + 1));
        data.put("reply", prepared.reply().reply());
        data.put("facts", facts);
        data.put("missingFields", facts.missing());
        data.put("toolCalls", prepared.toolCalls());
        data.put(
          "quoteDraft",
          facts.serviceItemIds().isEmpty()
            ? null
            : tools.draft(facts.serviceItemIds())
        );
        assistant.commit(prepared);
        if (
          mapper.complete(
            current.id,
            claim.token(),
            json.writeValueAsString(facts)
          ) != 1 ||
          mapper.saveTurn(
            claim.turn().id,
            json.writeValueAsString(
              Map.of("requestId", requestId, "data", data)
            )
          ) != 1
        ) throw ApiException.of(ErrorCode.RESOURCE_VERSION_CONFLICT);
        return new CommandResult(
          200,
          null,
          json.valueToTree(data),
          false,
          null
        );
      });
    } catch (RejectedExecutionException e) {
      fail(claim, ErrorCode.AI_QUEUE_FULL);
      throw ApiException.of(ErrorCode.AI_QUEUE_FULL);
    } catch (InterruptedException e) {
      if (future != null) future.cancel(true);
      Thread.currentThread().interrupt();
      fail(claim, ErrorCode.AI_UNAVAILABLE);
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    } catch (TimeoutException e) {
      if (future != null) future.cancel(true);
      fail(claim, ErrorCode.AI_UNAVAILABLE);
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    } catch (ExecutionException | IllegalArgumentException e) {
      fail(claim, ErrorCode.AI_UNAVAILABLE);
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    } catch (ApiException e) {
      fail(claim, e.errorCode());
      throw e;
    } catch (RuntimeException e) {
      fail(claim, ErrorCode.RETRYABLE_STORAGE_ERROR);
      throw ApiException.of(ErrorCode.RETRYABLE_STORAGE_ERROR);
    }
  }

  private Claim claim(
    long actor,
    String id,
    String key,
    String hash,
    String text,
    int expected
  ) {
    lockActor(actor);
    var row = found(mapper.lock(id, actor));
    var turn = mapper.turn(row.id, key);
    if (turn != null && !hash.equals(turn.requestHash)) throw ApiException.of(
      ErrorCode.IDEMPOTENCY_KEY_REUSED
    );
    if (turn != null && "SUCCEEDED".equals(turn.status)) {
      var old = json.readTree(turn.responseJson);
      return new Claim(
        row,
        turn,
        null,
        new CommandResult(
          200,
          null,
          old.path("data"),
          true,
          old.path("requestId").asString()
        )
      );
    }
    if (row.revision != expected) throw ApiException.of(
      ErrorCode.RESOURCE_VERSION_CONFLICT
    );
    if (
      row.claimToken != null &&
      row.leaseUntil != null &&
      row.leaseUntil.isAfter(mapper.now())
    ) throw ApiException.of(ErrorCode.AI_QUEUE_FULL);
    if (
      mapper.recent(actor) >= p.perMinute() ||
      (turn == null && mapper.countTurns(row.id) >= 100)
    ) throw ApiException.of(ErrorCode.RATE_LIMITED);
    if (row.claimToken != null) mapper.expireTurns(row.id);
    if (turn != null) {
      if (
        turn.attempts >= 3 || mapper.retry(turn.id) != 1
      ) throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    } else {
      turn = new ConversationMapper.Turn();
      turn.conversationId = row.id;
      turn.requestKey = key;
      turn.requestHash = hash;
      turn.messageText = text;
      mapper.insertTurn(turn);
    }
    String token = UUID.randomUUID().toString();
    if (mapper.claim(row.id, token) != 1) throw ApiException.internal();
    return new Claim(row, turn, token, null);
  }

  private void fail(Claim c, ErrorCode error) {
    try {
      tx.executeWithoutResult(s -> {
        if (mapper.clearClaim(c.row().id, c.token()) == 1) mapper.failTurn(
          c.turn().id,
          error.name()
        );
      });
    } catch (org.springframework.dao.DataAccessException ignored) {
      /* 保留已领取任务，租约到期后可恢复；不泄露对话文本。 */
    }
  }

  private void lockActor(long actor) {
    String role = mapper.lockActor(actor);
    if (role == null) throw ApiException.of(ErrorCode.UNAUTHENTICATED);
    if (!Set.of("OWNER", "TECH").contains(role)) throw ApiException.of(
      ErrorCode.FORBIDDEN
    );
  }

  private ConversationMapper.Row found(ConversationMapper.Row row) {
    if (row == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    return row;
  }

  private String publicId(String id) {
    if (
      id == null ||
      !id.matches(
        "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
      )
    ) throw invalid("/conversationId", "会话ID不合法");
    return id;
  }

  private ApiException invalid(String path, String why) {
    return ApiException.invalid(FieldErrors.invalidFormat(path, why));
  }

  private Map<String, Object> view(ConversationMapper.Row row) {
    return Map.of(
      "conversationId",
      row.publicId,
      "revision",
      "" + row.revision,
      "facts",
      json.readTree(row.facts),
      "createdAt",
      UtcTimes.format(row.createdAt),
      "updatedAt",
      UtcTimes.format(row.updatedAt)
    );
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
