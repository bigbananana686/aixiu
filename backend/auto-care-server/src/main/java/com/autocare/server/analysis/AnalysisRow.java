package com.autocare.server.analysis;

import java.time.LocalDateTime;

public class AnalysisRow {

  public long jobId, quoteId;
  public int quoteVersion;
  public String contentHash, findings, missingEvidence, citations, limitations, documentVersions;
  public LocalDateTime analyzedAt;
}
