package com.autocare.ai.knowledge;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/** Spring AI 2.0.1官方Embedding适配；独立凭据，不假定聊天厂商支持Embedding。 */
public final class SpringEmbeddingPort implements EmbeddingPort {

  private final EmbeddingModel model;
  private final String id;
  private final int dimensions;

  public SpringEmbeddingPort(EmbeddingModel model, String id, int dimensions) {
    this.model = model;
    this.id = id;
    this.dimensions = dimensions;
  }

  public static EmbeddingPort openAi(
    URI baseUrl,
    String secret,
    String modelId,
    int dimensions,
    Duration timeout
  ) {
    var options = OpenAiEmbeddingOptions.builder()
      .baseUrl(baseUrl.toString())
      .apiKey(secret)
      .model(modelId)
      .dimensions(dimensions)
      .timeout(timeout)
      .maxRetries(0)
      .build();
    return new SpringEmbeddingPort(
      OpenAiEmbeddingModel.builder().options(options).build(),
      modelId,
      dimensions
    );
  }

  public String modelId() {
    return id;
  }

  public int dimensions() {
    return dimensions;
  }

  public List<float[]> embed(List<String> texts) {
    if (
      texts.isEmpty() || texts.size() > 2048
    ) throw new IllegalArgumentException("Embedding batch bound exceeded");
    return model.embed(texts);
  }
}
