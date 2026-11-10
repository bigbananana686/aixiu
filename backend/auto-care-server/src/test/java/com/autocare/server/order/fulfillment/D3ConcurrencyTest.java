package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class D3ConcurrencyTest extends D3Support {

  @Test
  void c01TwentyIndependentRequestsHaveExactlyOneWinner() throws Exception {
    String body =
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      day +
      "T05:00:00.000Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"C01 synthetic\"}";
    var ready = new CountDownLatch(20);
    var go = new CountDownLatch(1);
    List<Reply> replies = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(20)) {
      List<Future<Reply>> fs = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        String k = key();
        fs.add(
          pool.submit(() -> {
            ready.countDown();
            assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
            return unchecked("POST", "", 1, body, k);
          })
        );
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      for (var f : fs) replies.add(f.get(30, TimeUnit.SECONDS));
    }
    for (var r : replies)
      if (r.status() == 201) ids.add(r.data().path("orderId").asLong());
    assertThat(
      replies
        .stream()
        .filter(r -> r.status() == 201)
        .count()
    ).isOne();
    assertThat(
      replies
        .stream()
        .filter(r -> r.status() != 201)
        .toList()
    )
      .hasSize(19)
      .allSatisfy(r -> expect(r, 409, "SLOT_CONFLICT"));
    noOverlap();
    System.out.println(
      "D3 C01 independent HTTP clients=20 success=1 conflicts=19 overlaps=0"
    );
  }

  @Test
  void c05ExtraFirstPreventsLaterBookingAndBookingFirstPreventsExtra()
    throws Exception {
    long id = servicing();
    long pending = q(
      submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1))
    );
    String body =
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      day +
      "T06:45:00.000Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"C05 synthetic\"}";
    expect(send("POST", "", 1, body, key()), 409, "SLOT_CONFLICT");
    expect(decide(id, pending, true, 1, "2", key(), "not needed"), 200, null);
    long other = create(0, "06:45");
    var before = bounds(id);
    expect(
      submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)),
      409,
      "SLOT_CONFLICT"
    );
    unchanged(id, before);
    noOverlap();
  }

  @Test
  void c05SimultaneousExtraAndBookingCannotOverlap() throws Exception {
    long id = servicing();
    String extra = submission(
      id,
      "EXTRA",
      item(1) + "," + line("extra-1", 2, 1)
    );
    String body =
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      day +
      "T06:45:00.000Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"C05 race\"}";
    var rs = race(
      () -> unchecked("POST", "/" + id + "/quotes", 3, extra, key()),
      () -> unchecked("POST", "", 1, body, key())
    );
    if (rs.get(1).status() == 201) ids.add(
      rs.get(1).data().path("orderId").asLong()
    );
    assertThat(
      rs.stream().map(Reply::status).sorted().toList()
    ).containsExactly(201, 409);
    rs.stream()
      .filter(r -> r.status() == 409)
      .forEach(r -> expect(r, 409, "SLOT_CONFLICT"));
    noOverlap();
  }

  @Test
  void c07LateConfirmAndExpiryRaceOnlyExpiresOnce() throws Exception {
    long id = servicing();
    long q = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    db.update(
      "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
      q
    );
    var outcomes = race(
      () ->
        unchecked(
          "POST",
          "/" + id + "/quotes/" + q + "/confirm",
          1,
          "{\"version\":\"2\"}",
          key()
        ),
      () -> {
        expiry.expire(id, q, 2);
        return (Reply) null;
      }
    );
    expect(outcomes.get(0), 409, "QUOTE_EXPIRED");
    assertThat(expiry.expire(id, q, 2)).isFalse();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='EXPIRE_QUOTE'",
        Integer.class,
        id
      )
    ).isOne();
    noOverlap();
  }

  @org.springframework.beans.factory.annotation.Autowired
  com.autocare.server.order.booking.OrderMapper clock;

  @ParameterizedTest
  @ValueSource(ints = { -1, 0, 1 })
  void exactExtraDeadlineUsesDatabaseTime(int offset) throws Exception {
    long id = servicing(),
      q = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    LocalDateTime deadline = LocalDateTime.of(2027, 1, 1, 0, 0);
    db.update("UPDATE t_order_quote SET expires_at=? WHERE id=?", deadline, q);
    var tx = new org.springframework.transaction.support.TransactionTemplate(
      manager
    );
    installOwner();
    try {
      tx.executeWithoutResult(status -> {
        try {
          var now = deadline.plusNanos(offset * 1000000L);
          db.execute(
            "SET timestamp=" +
              java.math.BigDecimal.valueOf(
                now.toInstant(ZoneOffset.UTC).toEpochMilli(),
                3
              ).toPlainString()
          );
          assertThat(clock.dbNowUtc()).isEqualTo(now);
          java.util.function.Supplier<Object> decide = () ->
            service.decide(
              1,
              "" + id,
              "" + q,
              json.readTree("{\"version\":\"2\"}"),
              key(),
              UUID.randomUUID().toString(),
              false
            );
          if (offset < 0) decide.get();
          else assertThatThrownBy(decide::get).isInstanceOfSatisfying(
            com.autocare.common.error.ApiException.class,
            e -> assertThat(e.errorCode().name()).isEqualTo("QUOTE_EXPIRED")
          );
          status.setRollbackOnly();
          System.out.println(
            "D3 EXTRA_DEADLINE offsetMs=" + offset + " dbNow=" + now
          );
        } finally {
          db.execute("SET timestamp=DEFAULT");
        }
      });
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test
  void applicationSessionHasRequiredMysqlSettings() {
    var s = db.queryForMap(
      "SELECT VERSION() version,@@transaction_isolation isolation_level,@@session.time_zone timezone,@@session.innodb_lock_wait_timeout lock_wait"
    );
    assertThat(s.get("version").toString()).startsWith("8.4.");
    assertThat(s.get("isolation_level")).isEqualTo("READ-COMMITTED");
    assertThat(s.get("timezone").toString()).isIn("+00:00", "UTC");
    assertThat(s.get("lock_wait").toString()).isEqualTo("5");
    System.out.println("D3 APPLICATION_DB_SESSION " + s);
    noOverlap();
  }
}
