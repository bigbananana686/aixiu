package com.autocare.server.knowledge;

import java.time.LocalDateTime;

/** 内部元信息，不直接序列化：路径/文本/向量均不能进入管理列表。 */
public class KnowledgeRow {

  public Long id, latestJobId;
  public int version, desiredVersion;
  public Integer activeVersion, pageCount, charCount, embeddingDimensions;
  public long sizeBytes;
  public boolean published;
  public String documentKey, title, modelTags, source, contentType, filePath, indexPath, indexSpace, embeddingModel, chunkStrategy, indexStatus, staleReason;
  public LocalDateTime createdAt;
}
