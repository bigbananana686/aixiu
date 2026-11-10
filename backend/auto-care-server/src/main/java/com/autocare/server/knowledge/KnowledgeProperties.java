package com.autocare.server.knowledge;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("autocare.knowledge")
public record KnowledgeProperties(
  @DefaultValue("./data/knowledge") Path storage,
  @DefaultValue("false") boolean embeddingEnabled,
  @DefaultValue("https://api.openai.com/v1") URI embeddingBaseUrl,
  @DefaultValue("text-embedding-3-small")
  @NotBlank
  @Size(max = 64)
  String embeddingModel,
  @DefaultValue("1536") @Min(1) @Max(4096) int dimensions,
  @DefaultValue("false") boolean analysisEnabled,
  @DefaultValue("https://api.deepseek.com") URI chatBaseUrl,
  @DefaultValue("deepseek-chat") @NotBlank @Size(max = 64) String chatModel,
  @DefaultValue("20s") Duration timeout,
  @DefaultValue("100") @Min(1) @Max(1000) int maxPending,
  @DefaultValue("20") @Min(1) @Max(100) int maxActorPending,
  @DefaultValue("6") @Min(1) @Max(100) int perMinute
) {
  public KnowledgeProperties {
    if (
      storage == null ||
      timeout == null ||
      timeout.isZero() ||
      timeout.isNegative() ||
      timeout.compareTo(Duration.ofSeconds(30)) > 0
    ) throw new IllegalArgumentException("Invalid knowledge storage/timeout");
    for (URI u : new URI[] { embeddingBaseUrl, chatBaseUrl })
      if (
        u == null ||
        u.getHost() == null ||
        !java.util.Set.of("http", "https").contains(u.getScheme()) ||
        u.getUserInfo() != null
      ) throw new IllegalArgumentException("Invalid model endpoint");
  }
}
