package com.autocare.server.ai;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "autocare.tasks.enabled=false",
    "autocare.ai.booking.timeout=300ms",
    "autocare.ai.booking.concurrency=1",
    "autocare.ai.booking.per-minute=2",
  }
)
@Import(BookingDraftLimitsTest.Config.class)
class BookingDraftLimitsTest extends DraftTestSupport {

  @TestConfiguration
  static class Config {

    @Bean
    @Primary
    StubParser stubParser() {
      return new StubParser();
    }
  }

  @AfterEach
  void clean() {
    db.update("DELETE FROM t_ai_call_log WHERE scene='BOOKING_DRAFT'");
  }

  @Test
  void saturatedCapacityAndTimeout_areBoundedThenRecover() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    stub.answer = () -> {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return draft();
    };
    try {
      long start = System.nanoTime();
      var first = pool.submit(() -> send(1, "{\"text\":\"抖动\"}", null));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      expect(send(2, "{\"text\":\"抖动\"}", null), 429, "AI_QUEUE_FULL");
      expect(first.get(5, TimeUnit.SECONDS), 503, "AI_UNAVAILABLE");
      assertThat(
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      ).isLessThan(2500);
      release.countDown();
      stub.answer = DraftTestSupport::draft;
      for (int i = 0; i < 20; i++) {
        try {
          expect(send(2, "{\"text\":\"抖动\"}", null), 200, null);
          break;
        } catch (AssertionError e) {
          if (i == 19) throw e;
          Thread.sleep(10);
        }
      }
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM t_ai_call_log WHERE outcome='TIMEOUT'",
          Integer.class
        )
      ).isEqualTo(1);
      expect(send(2, "{\"text\":\"抖动\"}", null), 429, "RATE_LIMITED");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
