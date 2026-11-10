package com.autocare.server.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("autocare.ai.booking")
public record BookingAiProperties(
  @DefaultValue("false") boolean enabled,
  @DefaultValue("https://api.deepseek.com") URI baseUrl,
  @DefaultValue("deepseek-chat") String model,
  @DefaultValue("20s") Duration timeout,
  @DefaultValue("2") int concurrency,
  @DefaultValue("6") int perMinute
) {
  public BookingAiProperties {
    if (
      baseUrl == null ||
      model == null ||
      !model.matches("[A-Za-z0-9_.:/-]{1,64}") ||
      timeout == null ||
      timeout.isNegative() ||
      timeout.isZero() ||
      timeout.compareTo(Duration.ofSeconds(20)) > 0 ||
      concurrency < 1 ||
      concurrency > 32 ||
      perMinute < 1 ||
      perMinute > 100 ||
      baseUrl.getHost() == null ||
      baseUrl.getUserInfo() != null ||
      baseUrl.getQuery() != null ||
      !(
        "https".equals(baseUrl.getScheme()) ||
        ("http".equals(baseUrl.getScheme()) &&
          SetHolder.LOCAL.contains(baseUrl.getHost()))
      )
    ) throw new IllegalArgumentException(
      "Invalid booking AI limits or endpoint"
    );
  }

  private static class SetHolder {

    static final java.util.Set<String> LOCAL = java.util.Set.of(
      "localhost",
      "127.0.0.1"
    );
  }
}
