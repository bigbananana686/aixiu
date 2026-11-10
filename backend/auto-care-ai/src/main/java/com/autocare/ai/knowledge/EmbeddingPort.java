package com.autocare.ai.knowledge;

import java.util.List;

/** 模型、维度一起标识向量空间；不以随机/词频向量替代真实Embedding。 */
public interface EmbeddingPort {
  String modelId();
  int dimensions();
  List<float[]> embed(List<String> texts);
}
