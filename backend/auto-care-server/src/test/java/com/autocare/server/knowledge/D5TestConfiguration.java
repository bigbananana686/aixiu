package com.autocare.server.knowledge;

import com.autocare.ai.analysis.*;
import com.autocare.ai.knowledge.*;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class D5TestConfiguration {

  @Bean
  public FakeEmbeddings fakeEmbeddings() {
    return new FakeEmbeddings();
  }

  @Bean
  public SpringVectorIndex controlledVectorStore(
    FakeEmbeddings embeddings,
    KnowledgeFiles files
  ) {
    int port = Integer.parseInt(
      System.getenv().getOrDefault("AUTOCARE_VECTOR_REDIS_PORT", "16379")
    );
    return SpringVectorIndex.redis(
      embeddings,
      files.root(),
      "127.0.0.1",
      port,
      null
    );
  }

  @Bean
  public FakeAnalyzer fakeAnalyzer() {
    return new FakeAnalyzer();
  }

  public static class FakeEmbeddings implements EmbeddingPort {

    public volatile boolean unavailable;

    public String modelId() {
      return "controlled-embedding-v1";
    }

    public int dimensions() {
      return 2;
    }

    public List<float[]> embed(List<String> texts) {
      if (unavailable) throw new IllegalStateException(
        "controlled dependency failure"
      );
      return texts
        .stream()
        .map(s ->
          s.contains("无关") ? new float[] { 0, 1 } : new float[] { 1, 0 }
        )
        .toList();
    }
  }

  public static class FakeAnalyzer implements QuoteAnalyzer {

    public volatile String mode = "valid";
    public volatile List<Evidence> seen = List.of();
    public volatile Context seenQuote;

    public Answer analyze(Context quote, List<Evidence> candidates) {
      seen = candidates;
      seenQuote = quote;
      if (mode.equals("unavailable")) throw new IllegalStateException(
        "controlled model failure"
      );
      if (mode.equals("forged")) return new Answer(
        new Result(
          List.of(new Finding("f1", "假引用", "base-1", List.of("c1"))),
          List.of(),
          List.of(
            new Citation("c1", "foreign-doc", "99", "fake", "未知页", "假的")
          ),
          List.of("人工核对")
        ),
        "controlled-chat",
        null,
        null,
        null
      );
      Evidence e = candidates.getFirst();
      return new Answer(
        new Result(
          List.of(
            new Finding(
              "f1",
              "资料包含机油机滤保养说明",
              "base-1",
              List.of("c1")
            )
          ),
          List.of("需人工判断实际车况"),
          List.of(
            new Citation(
              "c1",
              e.documentKey(),
              e.documentVersion(),
              e.chunkKey(),
              e.sourceLocator(),
              e.documentTitle()
            )
          ),
          List.of("合成测试资料，不作维修决定")
        ),
        "controlled-chat",
        null,
        null,
        null
      );
    }
  }
}
