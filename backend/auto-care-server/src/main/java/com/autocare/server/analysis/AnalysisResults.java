package com.autocare.server.analysis;

import com.autocare.server.knowledge.KnowledgeMapper;
import com.autocare.server.order.lifecycle.OrderLifecycleMapper;
import com.autocare.server.order.quote.*;
import com.autocare.server.task.JobRow;
import com.autocare.server.web.UtcTimes;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisResults {

  private final AnalysisMapper mapper;
  private final QuoteMapper quotes;
  private final OrderLifecycleMapper orders;
  private final KnowledgeMapper knowledge;
  private final ObjectMapper json;

  public AnalysisResults(
    AnalysisMapper mapper,
    QuoteMapper quotes,
    OrderLifecycleMapper orders,
    KnowledgeMapper knowledge,
    ObjectMapper json
  ) {
    this.mapper = mapper;
    this.quotes = quotes;
    this.orders = orders;
    this.knowledge = knowledge;
    this.json = json;
  }

  public record Summary(
    String jobId,
    String status,
    boolean resultStale,
    String staleReason,
    String contentHash,
    String analyzedAt
  ) {}

  public Summary summary(long actor, long order, QuoteRow quote) {
    JobRow job = mapper.latest(actor, quote.getId());
    if (job == null) return null;
    var r = mapper.result(job.id);
    String reason = stale(job, r);
    return new Summary(
      "" + job.id,
      job.status,
      reason != null,
      reason,
      json.readTree(job.payload).path("content_hash").asString(),
      r == null ? null : UtcTimes.format(r.analyzedAt)
    );
  }

  public String stale(JobRow job, AnalysisRow result) {
    var p = json.readTree(job.payload);
    long orderId = p.path("order_id").asLong();
    var q = quotes.find(orderId, job.resourceId);
    var order = orders.find(orderId);
    if (q == null || order == null) return "NO_LONGER_CURRENT";
    if (
      !Objects.equals(q.getContentHash(), p.path("content_hash").asString())
    ) return "CONTENT_CHANGED";
    if ("SUPERSEDED".equals(q.getStatus())) return "SUPERSEDED";
    if (
      !Objects.equals(order.getPendingQuoteId(), q.getId()) &&
      !Objects.equals(order.getApprovedQuoteId(), q.getId())
    ) return "NO_LONGER_CURRENT";
    if (result != null && result.documentVersions != null) {
      var original = new TreeMap<String, String>();
      for (var d : json.readTree(result.documentVersions))
        original.put(
          d.path("documentKey").asString(),
          d.path("version").asString()
        );
      var current = new TreeMap<String, String>();
      for (var d : knowledge.active(order.getModelSnapshot()))
        current.put(d.documentKey, "" + d.version);
      if (!original.equals(current)) return "EVIDENCE_VERSION_CHANGED";
    }
    return null;
  }

  public Map<String, Object> result(AnalysisRow r) {
    if (r == null) return null;
    return Map.of(
      "kind",
      "QUOTE_ANALYSIS",
      "quoteId",
      "" + r.quoteId,
      "version",
      "" + r.quoteVersion,
      "contentHash",
      r.contentHash,
      "findings",
      json.readTree(r.findings),
      "missingEvidence",
      json.readTree(r.missingEvidence),
      "citations",
      json.readTree(r.citations),
      "limitations",
      json.readTree(r.limitations)
    );
  }
}
