package com.autocare.server.analysis;

import com.autocare.ai.knowledge.KnowledgeFiles;
import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.command.*;
import com.autocare.server.knowledge.KnowledgeInputs;
import com.autocare.server.order.lifecycle.*;
import com.autocare.server.order.quote.*;
import com.autocare.server.task.*;
import com.autocare.server.web.UtcTimes;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

@Service
public class QuoteAnalysisService {

  private final QuoteMapper quotes;
  private final OrderLifecycleMapper orders;
  private final OrderLifecycleService lifecycle;
  private final CommandExecutor commands;
  private final JobService jobs;
  private final JobAdmission admission;
  private final JobAuthorization.AccessMapper access;
  private final ObjectMapper json;

  public QuoteAnalysisService(
    QuoteMapper quotes,
    OrderLifecycleMapper orders,
    OrderLifecycleService lifecycle,
    CommandExecutor commands,
    JobService jobs,
    JobAdmission admission,
    JobAuthorization.AccessMapper access,
    ObjectMapper json
  ) {
    this.quotes = quotes;
    this.orders = orders;
    this.lifecycle = lifecycle;
    this.commands = commands;
    this.jobs = jobs;
    this.admission = admission;
    this.access = access;
    this.json = json;
  }

  public CommandResult submit(
    long actor,
    String orderId,
    JsonNode body,
    String key,
    String requestId
  ) {
    Role role = lifecycle.currentRole(actor, null);
    if (role != Role.OWNER && role != Role.TECH) throw ApiException.of(
      ErrorCode.FORBIDDEN
    );
    long order = KnowledgeInputs.id(orderId, "orderId");
    if (body == null || !body.isObject()) throw ApiException.invalid(
      FieldErrors.invalidType("/", "必须为对象")
    );
    for (String field : body.propertyNames())
      if (
        !Set.of("quoteId", "version", "contentHash").contains(field)
      ) throw ApiException.invalid(
        FieldErrors.unknownField("/" + field, "未知字段")
      );
    String quoteId = text(body, "quoteId"),
      version = text(body, "version"),
      hash = text(body, "contentHash");
    long quote = KnowledgeInputs.id(quoteId, "quoteId");
    if (
      !version.matches("[1-9][0-9]{0,9}") ||
      Long.parseLong(version) > Integer.MAX_VALUE
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/version", "版本格式不合法")
    );
    if (!hash.matches("[a-f0-9]{64}")) throw ApiException.invalid(
      FieldErrors.invalidFormat("/contentHash", "必须为SHA256")
    );
    OrderLifecycleService.requireVisible(orders.find(order), actor, role);
    if (quotes.find(order, quote) == null) throw ApiException.of(
      ErrorCode.RESOURCE_NOT_FOUND
    );
    IdempotencyKey.parse(key);
    return commands.execute(
      CommandSpec.of("createQuoteAnalysis")
        .pathResource("orderId", orderId)
        .orderLinked(),
      key,
      Map.of("quoteId", quoteId, "version", version, "contentHash", hash),
      requestId,
      () -> {
        // 只有共享订单锁，无技师资源锁；角色与归属一直受保护到持久接收提交。
        String currentRole = access.role(actor);
        var current = access.order(order);
        if (
          current == null ||
          !(
            ("OWNER".equals(currentRole) && current.ownerId == actor) ||
            ("TECH".equals(currentRole) && current.technicianId == actor)
          )
        ) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
        var q = quotes.find(order, quote);
        if (q == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
        if (
          q.getVersion() != Integer.parseInt(version) ||
          !hash.equals(q.getContentHash())
        ) throw ApiException.of(ErrorCode.QUOTE_VERSION_CONFLICT);
        admission.reserve(actor);
        JobRow job = new JobRow();
        job.type = "QUOTE_ANALYSIS";
        job.actorId = actor;
        job.resourceId = quote;
        job.businessKey =
          "quote-analysis:" +
          actor +
          ":" +
          quote +
          ":" +
          KnowledgeFiles.hash(key);
        job.payload = json.writeValueAsString(
          Map.of(
            "order_id",
            "" + order,
            "quote_id",
            quoteId,
            "quote_version",
            Integer.parseInt(version),
            "content_hash",
            hash
          )
        );
        long jobId = jobs.enqueue(job);
        return new CommandOutcome<>(
          202,
          "/api/v1/jobs/" + jobId,
          Map.of(
            "jobId",
            "" + jobId,
            "type",
            "QUOTE_ANALYSIS",
            "status",
            "PENDING",
            "quoteId",
            quoteId,
            "version",
            version,
            "contentHash",
            hash,
            "acceptedAt",
            UtcTimes.format(java.time.Instant.now())
          ),
          order
        );
      },
      (who, resource) ->
        resource != null &&
        OrderLifecycleService.visible(
          orders.find(resource),
          who,
          lifecycle.currentRole(who, null)
        )
    );
  }

  private String text(JsonNode body, String field) {
    if (!body.has(field)) throw ApiException.invalid(
      FieldErrors.required("/" + field)
    );
    if (body.path(field).isNull()) throw ApiException.invalid(
      FieldErrors.nullNotAllowed("/" + field)
    );
    if (!body.path(field).isString()) throw ApiException.invalid(
      FieldErrors.invalidType("/" + field, "必须为字符串")
    );
    return body.path(field).asString();
  }
}
