package com.autocare.server.analysis;

import com.autocare.ai.analysis.*;
import com.autocare.ai.knowledge.*;
import com.autocare.common.error.*;
import com.autocare.server.knowledge.*;
import com.autocare.server.order.lifecycle.OrderLifecycleMapper;
import com.autocare.server.order.quote.QuoteMapper;
import com.autocare.server.task.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
public class QuoteAnalysisHandler implements JobHandler {

  private final QuoteMapper quotes;
  private final OrderLifecycleMapper orders;
  private final KnowledgeMapper knowledge;
  private final KnowledgeIndex index;
  private final QuoteAnalyzer analyzer;
  private final AnalysisMapper mapper;
  private final ObjectMapper json;
  private final KnowledgeProperties properties;

  public QuoteAnalysisHandler(
    QuoteMapper quotes,
    OrderLifecycleMapper orders,
    KnowledgeMapper knowledge,
    KnowledgeIndex index,
    QuoteAnalyzer analyzer,
    AnalysisMapper mapper,
    ObjectMapper json,
    KnowledgeProperties properties
  ) {
    this.quotes = quotes;
    this.orders = orders;
    this.knowledge = knowledge;
    this.index = index;
    this.analyzer = analyzer;
    this.mapper = mapper;
    this.json = json;
    this.properties = properties;
  }

  public String type() {
    return "QUOTE_ANALYSIS";
  }

  public Consumer<JobRow> prepare(JobLease lease, JobRow job) {
    if (
      TransactionSynchronizationManager.isActualTransactionActive()
    ) throw new IllegalStateException("Analysis requires no transaction");
    var payload = json.readTree(job.payload);
    long orderId = payload.path("order_id").asLong();
    var quote = quotes.find(orderId, job.resourceId);
    var order = orders.find(orderId);
    if (quote == null || order == null) throw ApiException.of(
      ErrorCode.JOB_NOT_RETRYABLE
    );
    var items = quotes.items(quote.getId());
    var context = new QuoteAnalyzer.Context(
      "" + quote.getId(),
      "" + quote.getVersion(),
      quote.getContentHash(),
      order.getModelSnapshot(),
      json.writeValueAsString(
        Map.of(
          "items",
          items,
          "totalAmount",
          quote.getTotalAmount(),
          "currency",
          quote.getCurrency(),
          "totalMinutes",
          quote.getTotalMinutes()
        )
      )
    );
    long start = System.nanoTime();
    String documents = "[]";
    try {
      var sources = knowledge
        .active(order.getModelSnapshot())
        .stream()
        .map(d ->
          new KnowledgeIndex.Source(
            d.documentKey,
            "" + d.version,
            d.title,
            d.indexPath,
            d.indexSpace
          )
        )
        .toList();
      var search = index.search(
        sources,
        order.getModelSnapshot() +
          " " +
          items
            .stream()
            .map(i -> i.nameSnapshot())
            .collect(Collectors.joining(" "))
      );
      documents = json.writeValueAsString(search.versions());
      QuoteAnalyzer.Answer answer;
      if (search.evidence().isEmpty()) {
        answer = new QuoteAnalyzer.Answer(
          new QuoteAnalyzer.Result(
            List.of(),
            List.of("未检索到适用车型的已发布资料，无法核对技术依据"),
            List.of(),
            List.of("资料核对不替代维修判断，报价仍须人工确认")
          ),
          "not-called",
          null,
          null,
          null
        );
      } else answer = analyzer.analyze(context, search.evidence());
      var result = AnalysisValidator.validate(
        answer.result(),
        search.evidence(),
        items
          .stream()
          .map(i -> i.lineKey())
          .collect(Collectors.toSet())
      );
      if (!"not-called".equals(answer.model())) log(
        job,
        orderId,
        quote.getId(),
        context,
        documents,
        "SUCCEEDED",
        start,
        answer,
        null
      );
      AnalysisRow row = new AnalysisRow();
      row.jobId = job.id;
      row.quoteId = quote.getId();
      row.quoteVersion = quote.getVersion();
      row.contentHash = quote.getContentHash();
      row.findings = json.writeValueAsString(result.findings());
      row.missingEvidence = json.writeValueAsString(result.missingEvidence());
      row.citations = json.writeValueAsString(result.citations());
      row.limitations = json.writeValueAsString(result.limitations());
      row.documentVersions = documents;
      return current -> {
        if (mapper.insert(row) != 1) throw ApiException.internal();
      };
    } catch (
      AnalysisValidator.InvalidAnalysisException
      | IllegalArgumentException e
    ) {
      log(
        job,
        orderId,
        quote.getId(),
        context,
        documents,
        "VALIDATION_FAILED",
        start,
        null,
        "INVALID_ARGUMENT"
      );
      throw ApiException.of(
        ErrorCode.INVALID_ARGUMENT,
        "分析输出或引用未通过校验，须人工核对"
      );
    } catch (Exception e) {
      log(
        job,
        orderId,
        quote.getId(),
        context,
        documents,
        "FAILED",
        start,
        null,
        "AI_UNAVAILABLE"
      );
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    }
  }

  private void log(
    JobRow job,
    long order,
    long quote,
    QuoteAnalyzer.Context context,
    String documents,
    String outcome,
    long start,
    QuoteAnalyzer.Answer answer,
    String error
  ) {
    mapper.call(
      job.actorId,
      order,
      quote,
      KnowledgeFiles.hash(json.writeValueAsString(context)),
      answer == null ? properties.chatModel() : answer.model(),
      documents,
      outcome,
      Math.min(600000, (System.nanoTime() - start) / 1_000_000),
      answer == null ? null : answer.promptTokens(),
      answer == null ? null : answer.completionTokens(),
      answer == null ? null : answer.totalTokens(),
      error
    );
  }
}
