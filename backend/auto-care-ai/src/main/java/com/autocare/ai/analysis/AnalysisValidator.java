package com.autocare.ai.analysis;

import static com.autocare.ai.analysis.QuoteAnalyzer.*;

import com.autocare.ai.knowledge.Evidence;
import java.util.*;

/** 引用身份由授权候选核对，不允许模型自行扩展资料范围。语义质量另由G3-04评测。 */
public final class AnalysisValidator {

  private AnalysisValidator() {}

  public static Result validate(
    Result r,
    List<Evidence> candidates,
    Set<String> lineKeys
  ) {
    if (
      r == null ||
      r.findings() == null ||
      r.citations() == null ||
      r.missingEvidence() == null ||
      r.limitations() == null ||
      r.findings().size() > 50 ||
      r.citations().size() > 50 ||
      r.missingEvidence().size() > 50 ||
      r.limitations().size() > 10 ||
      candidates.size() > 8
    ) throw new InvalidAnalysisException();
    var ids = new HashSet<String>();
    for (var c : r.citations()) {
      if (
        c == null || !text(c.citationId(), 32) || !ids.add(c.citationId())
      ) throw new InvalidAnalysisException();
      boolean found = candidates
        .stream()
        .anyMatch(
          e ->
            Objects.equals(e.documentKey(), c.documentKey()) &&
            Objects.equals(e.documentVersion(), c.documentVersion()) &&
            Objects.equals(e.chunkKey(), c.chunkKey()) &&
            Objects.equals(e.sourceLocator(), c.sourceLocator()) &&
            Objects.equals(e.documentTitle(), c.documentTitle())
        );
      if (!found) throw new InvalidAnalysisException();
    }
    var findings = new HashSet<String>();
    for (var f : r.findings()) {
      if (
        f == null ||
        !text(f.findingId(), 32) ||
        !findings.add(f.findingId()) ||
        !text(f.statement(), 500) ||
        (f.relatedLineKey() != null &&
          !lineKeys.contains(f.relatedLineKey())) ||
        f.citationIds() == null ||
        f.citationIds().isEmpty() ||
        f.citationIds().size() > 10 ||
        !ids.containsAll(f.citationIds()) ||
        new HashSet<>(f.citationIds()).size() != f.citationIds().size()
      ) throw new InvalidAnalysisException();
    }
    if (
      r
        .missingEvidence()
        .stream()
        .anyMatch(s -> !text(s, 500)) ||
      r
        .limitations()
        .stream()
        .anyMatch(s -> !text(s, 200))
    ) throw new InvalidAnalysisException();
    if (
      r.findings().isEmpty() && r.missingEvidence().isEmpty()
    ) throw new InvalidAnalysisException();
    return r;
  }

  private static boolean text(String s, int max) {
    return s != null && !s.isBlank() && s.codePointCount(0, s.length()) <= max;
  }

  public static class InvalidAnalysisException extends RuntimeException {}
}
