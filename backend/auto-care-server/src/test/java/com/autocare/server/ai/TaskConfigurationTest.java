package com.autocare.server.ai;

import static org.assertj.core.api.Assertions.*;

import com.autocare.server.task.TaskProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TaskConfigurationTest {

  @Configuration
  @EnableConfigurationProperties({
    TaskProperties.class,
    BookingAiProperties.class,
  })
  static class Config {}

  @Test
  void typedBinding_appliesOverridesAndDurationUnits() {
    new ApplicationContextRunner()
      .withUserConfiguration(Config.class)
      .withPropertyValues(
        "autocare.tasks.workers=3",
        "autocare.tasks.queue-capacity=0",
        "autocare.tasks.lease=30s",
        "autocare.tasks.heartbeat=5s",
        "autocare.ai.booking.timeout=1500ms"
      )
      .run(c -> {
        assertThat(c).hasNotFailed();
        assertThat(c.getBean(TaskProperties.class).workers()).isEqualTo(3);
        assertThat(c.getBean(TaskProperties.class).lease()).isEqualTo(
          java.time.Duration.ofSeconds(30)
        );
        assertThat(c.getBean(BookingAiProperties.class).timeout()).isEqualTo(
          java.time.Duration.ofMillis(1500)
        );
      });
  }

  @Test
  void invalidLeaseHeartbeat_failsStartup() {
    new ApplicationContextRunner()
      .withUserConfiguration(Config.class)
      .withPropertyValues("autocare.tasks.lease=0s")
      .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void totalModelWaitAbove20Seconds_failsStartup() {
    new ApplicationContextRunner()
      .withUserConfiguration(Config.class)
      .withPropertyValues("autocare.ai.booking.timeout=21s")
      .run(c -> assertThat(c).hasFailed());
  }
}
