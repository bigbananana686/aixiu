package com.autocare.server.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("autocare.vector")
public record VectorProperties(
  @DefaultValue("127.0.0.1") String host,
  @DefaultValue("16379") int port
) {
  public VectorProperties {
    if (
      host == null || host.isBlank() || port < 1 || port > 65535
    ) throw new IllegalArgumentException("Invalid vector endpoint");
  }
}
