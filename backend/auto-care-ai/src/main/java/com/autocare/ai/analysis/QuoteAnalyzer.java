package com.autocare.ai.analysis;

import com.autocare.ai.knowledge.Evidence;
import java.util.List;

/** 只读输入仅含报价快照/车型，不含地址、车牌和联系方式；没有写业务的Tool。 */
public interface QuoteAnalyzer {
  record Context(
    String quoteId,
    String version,
    String contentHash,
    String modelCode,
    String quoteJson
  ) {}

  record Finding(
    String findingId,
    String statement,
    String relatedLineKey,
    List<String> citationIds
  ) {}

  record Citation(
    String citationId,
    String documentKey,
    String documentVersion,
    String chunkKey,
    String sourceLocator,
    String documentTitle
  ) {}

  record Result(
    List<Finding> findings,
    List<String> missingEvidence,
    List<Citation> citations,
    List<String> limitations
  ) {}

  record Answer(
    Result result,
    String model,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens
  ) {}

  Answer analyze(Context quote, List<Evidence> candidates);
}
