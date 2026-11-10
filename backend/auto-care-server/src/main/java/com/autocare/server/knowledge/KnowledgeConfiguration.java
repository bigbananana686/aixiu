package com.autocare.server.knowledge;

import com.autocare.ai.analysis.*;
import com.autocare.ai.knowledge.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties({
  KnowledgeProperties.class,
  VectorProperties.class,
})
public class KnowledgeConfiguration {

  @Bean
  KnowledgeFiles knowledgeFiles(KnowledgeProperties p) {
    return new KnowledgeFiles(p.storage());
  }

  @Bean
  KnowledgeIndex knowledgeIndex(
    KnowledgeFiles files,
    EmbeddingPort embeddings,
    SpringVectorIndex vectors
  ) {
    return new KnowledgeIndex(files, embeddings, vectors);
  }

  @Bean
  @ConditionalOnMissingBean(SpringVectorIndex.class)
  SpringVectorIndex vectorIndex(
    EmbeddingPort embeddings,
    KnowledgeFiles files,
    VectorProperties p,
    Environment env
  ) {
    return SpringVectorIndex.redis(
      embeddings,
      files.root(),
      p.host(),
      p.port(),
      env.getProperty("VECTOR_REDIS_PASSWORD")
    );
  }

  @Bean
  @ConditionalOnMissingBean(EmbeddingPort.class)
  EmbeddingPort embeddings(KnowledgeProperties p, Environment env) {
    if (!p.embeddingEnabled()) return new EmbeddingPort() {
      public String modelId() {
        return p.embeddingModel();
      }

      public int dimensions() {
        return p.dimensions();
      }

      public java.util.List<float[]> embed(java.util.List<String> texts) {
        throw new IllegalStateException("Embedding disabled");
      }
    };
    return SpringEmbeddingPort.openAi(
      p.embeddingBaseUrl(),
      secret(env, "EMBEDDING_API_KEY"),
      p.embeddingModel(),
      p.dimensions(),
      p.timeout()
    );
  }

  @Bean
  @ConditionalOnMissingBean(QuoteAnalyzer.class)
  QuoteAnalyzer quoteAnalyzer(KnowledgeProperties p, Environment env) {
    if (!p.analysisEnabled()) return (quote, candidates) -> {
      throw new IllegalStateException("Quote model disabled");
    };
    String key = env.getProperty(
      "MODEL_API_KEY",
      env.getProperty("DEEPSEEK_API_KEY")
    );
    if (key == null || key.isBlank()) throw new IllegalStateException(
      "Quote AI enabled but MODEL_API_KEY missing"
    );
    return SpringQuoteAnalyzer.deepSeek(
      p.chatBaseUrl(),
      key,
      p.chatModel(),
      p.timeout()
    );
  }

  private static String secret(Environment env, String name) {
    String value = env.getProperty(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(
      "Embedding enabled but EMBEDDING_API_KEY missing"
    );
    return value;
  }
}
