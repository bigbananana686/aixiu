package com.autocare.server.task;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("autocare.tasks")
public record TaskProperties(
  @DefaultValue("true") boolean enabled,
  @DefaultValue("2") int workers,
  @DefaultValue("4") int queueCapacity,
  @DefaultValue("60s") Duration lease,
  @DefaultValue("10s") Duration heartbeat,
  @DefaultValue("auto-care") String workerName
) {
  public TaskProperties {
    if (
      workers < 1 ||
      workers > 32 ||
      queueCapacity < 0 ||
      queueCapacity > 1000 ||
      lease == null ||
      lease.compareTo(Duration.ofSeconds(1)) < 0 ||
      lease.compareTo(Duration.ofMinutes(10)) > 0 ||
      heartbeat == null ||
      heartbeat.isNegative() ||
      heartbeat.isZero() ||
      heartbeat.multipliedBy(2).compareTo(lease) >= 0 ||
      workerName == null ||
      !workerName.matches("[A-Za-z0-9_.-]{1,64}")
    ) throw new IllegalArgumentException(
      "Invalid task capacity, lease or worker name"
    );
  }
}
