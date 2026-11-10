package com.autocare.server.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.knowledge.EmbeddingPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class KnowledgeConfigurationTest {

  ApplicationContextRunner runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
    .withUserConfiguration(KnowledgeConfiguration.class);

  @Test
  void defaultsDoNotRequireRemoteCredentials() {
    runner.run(c -> {
      assertThat(c).hasNotFailed();
      assertThat(c).hasSingleBean(EmbeddingPort.class);
      assertThat(c.getBean(KnowledgeProperties.class).timeout()).hasSeconds(20);
    });
  }

  @Test
  void enabledEmbeddingRequiresItsOwnCredential() {
    runner
      .withPropertyValues(
        "autocare.knowledge.embedding-enabled=true",
        "EMBEDDING_API_KEY="
      )
      .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void enabledAnalysisRequiresModelCredential() {
    runner
      .withPropertyValues(
        "autocare.knowledge.analysis-enabled=true",
        "MODEL_API_KEY=",
        "DEEPSEEK_API_KEY="
      )
      .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void invalidDurationAndDimensionsFailStartup() {
    for (String value : new String[] {
      "autocare.knowledge.timeout=0s",
      "autocare.knowledge.timeout=31s",
      "autocare.knowledge.dimensions=0",
      "autocare.knowledge.max-pending=0",
    })
      runner.withPropertyValues(value).run(c -> assertThat(c).hasFailed());
  }

  @Test
  void configuredStorageAndDimensionsAreBound() {
    runner
      .withPropertyValues(
        "autocare.knowledge.storage=target/config-test",
        "autocare.knowledge.dimensions=256",
        "autocare.knowledge.timeout=3s"
      )
      .run(c -> {
        assertThat(c).hasNotFailed();
        var p = c.getBean(KnowledgeProperties.class);
        assertThat(p.dimensions()).isEqualTo(256);
        assertThat(p.storage().toString()).contains("config-test");
        assertThat(p.timeout()).hasSeconds(3);
      });
  }
}
