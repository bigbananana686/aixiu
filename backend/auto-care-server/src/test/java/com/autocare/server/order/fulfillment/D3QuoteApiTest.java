package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class D3QuoteApiTest extends D3Support {

  @Test
  void replacementKeepsDeadlineInvalidatesOldAndProtectsNewReservation()
    throws Exception {
    long id = arrived(),
      old = publish(id, 2);
    var before = bounds(id);
    var deadline = db.queryForObject(
      "SELECT expires_at FROM t_order_quote WHERE id=?",
      LocalDateTime.class,
      old
    );
    String body = submission(id, "INITIAL", item(3)),
      k = key();
    var reply = send("POST", "/" + id + "/quotes", 3, body, k);
    long next = q(reply);
    assertThat(
      reply.data().path("quote").path("expiresAt").asString()
    ).isEqualTo(com.autocare.server.web.UtcTimes.format(deadline));
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        old
      )
    ).isEqualTo("SUPERSEDED");
    expect(
      decide(id, old, false, 1, "1", key(), null),
      409,
      "QUOTE_VERSION_CONFLICT"
    );
    var newBounds = bounds(id);
    assertThat(expiry.expire(id, old, 1)).isFalse();
    unchanged(id, newBounds);
    assertThat(send("POST", "/" + id + "/quotes", 3, body, k).data()).isEqualTo(
      reply.data()
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_state_log WHERE order_id=?",
        Integer.class,
        id
      )
    ).isEqualTo(5);
    confirmed(id, next);
    var approvedBounds = bounds(id);
    assertThat(expiry.expire(id, old, 1)).isFalse();
    unchanged(id, approvedBounds);
  }

  @Test
  void expiredReplacementTerminatesRoundWithoutRenewal() throws Exception {
    long id = arrived(),
      old = publish(id, 2);
    String body = submission(id, "INITIAL", item(3));
    db.update(
      "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
      old
    );
    expect(
      send("POST", "/" + id + "/quotes", 3, body, key()),
      409,
      "QUOTE_EXPIRED"
    );
    assertThat(
      db.queryForObject(
        "SELECT state FROM t_service_order WHERE id=?",
        String.class,
        id
      )
    ).isEqualTo("ARRIVED");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_quote WHERE order_id=?",
        Integer.class,
        id
      )
    ).isOne();
    assertThat(
      db
        .queryForMap(
          "SELECT committed_end,effective_end FROM t_service_order WHERE id=?",
          id
        )
        .values()
    ).allMatch(v -> v.equals(bounds(id).get("committed_end")));
  }

  @Test
  void purposeSwitchIsBaselineConflictAndOldPendingRemains() throws Exception {
    long id = arrived(),
      old = publish(id, 1);
    var plan = bounds(id);
    expect(submit(id, "EXTRA", item(1)), 409, "QUOTE_VERSION_CONFLICT");
    unchanged(id, plan);
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        old
      )
    ).isEqualTo("PENDING");
  }

  @ParameterizedTest
  @ValueSource(strings = { "delete", "quantity", "service", "case" })
  void extraMustRetainApprovedIdentityAndQuantity(String change)
    throws Exception {
    long id = servicing();
    String old = switch (change) {
      case "delete" -> "";
      case "quantity" -> line("base-1", 1, 2) + ",";
      case "service" -> line("base-1", 2, 1) + ",";
      case "case" -> line("BASE-1", 1, 1) + ",";
      default -> throw new AssertionError();
    };
    var plan = bounds(id);
    expect(
      submit(id, "EXTRA", old + line("extra-1", 2, 1)),
      409,
      "QUOTE_VERSION_CONFLICT"
    );
    unchanged(id, plan);
  }

  @Test
  void multiRoundExtraRestoresSnapshotsAndCountsOnlyNewLines()
    throws Exception {
    long id = servicing();
    String baseline = item(1),
      second = baseline + "," + line("extra-1", 2, 1);
    db.update(
      "UPDATE t_service_item SET enabled=0,price=999.00,duration_minutes=999,version=2 WHERE id=1"
    );
    try {
      var first = submit(id, "EXTRA", second);
      long q2 = q(first);
      assertThat(
        first.data().path("quote").path("addedMinutes").asInt()
      ).isEqualTo(20);
      assertThat(
        first
          .data()
          .path("quote")
          .path("items")
          .get(0)
          .path("unitPrice")
          .asString()
      ).isEqualTo("199.00");
      var committed = db.queryForObject(
        "SELECT committed_end FROM t_service_order WHERE id=?",
        LocalDateTime.class,
        id
      );
      assertThat(
        first.data().path("quote").path("targetEnd").asString()
      ).isEqualTo(
        com.autocare.server.web.UtcTimes.format(committed.plusMinutes(20))
      );
      expect(
        send(
          "POST",
          "/" + id + "/complete",
          3,
          "{\"expectedRowVersion\":\"8\",\"actualFinish\":\"2026-11-01T00:00:00Z\",\"serviceResult\":\"done\"}",
          key()
        ),
        409,
        "ORDER_STATE_CONFLICT"
      );
      confirmed(id, q2);
      assertThat(
        db.queryForObject(
          "SELECT state FROM t_service_order WHERE id=?",
          String.class,
          id
        )
      ).isEqualTo("SERVICING");
      var third = submit(id, "EXTRA", second + "," + line("extra-2", 3, 1));
      long q3 = q(third);
      assertThat(
        third.data().path("quote").path("addedMinutes").asInt()
      ).isEqualTo(20);
      assertThat(
        third.data().path("quote").path("totalMinutes").asInt()
      ).isEqualTo(85);
      assertThat(
        third.data().path("quote").path("targetEnd").asString()
      ).isEqualTo(
        com.autocare.server.web.UtcTimes.format(committed.plusMinutes(40))
      );
      confirmed(id, q3);
      noOverlap();
    } finally {
      db.update(
        "UPDATE t_service_item SET enabled=1,price=199.00,duration_minutes=45,version=1 WHERE id=1"
      );
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "reject", "expire" })
  void extraReleaseRestoresApprovedAndPreservesCommitted(String action)
    throws Exception {
    long id = servicing();
    var before = bounds(id);
    long approved = db.queryForObject(
      "SELECT approved_quote_id FROM t_service_order WHERE id=?",
      Long.class,
      id
    );
    long next = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    if (action.equals("reject")) expect(
      decide(id, next, true, 1, "2", key(), "not needed"),
      200,
      null
    );
    else {
      db.update(
        "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
        next
      );
      assertThat(expiry.expire(id, next, 2)).isTrue();
    }
    assertThat(
      db.queryForObject(
        "SELECT state FROM t_service_order WHERE id=?",
        String.class,
        id
      )
    ).isEqualTo("SERVICING");
    assertThat(
      db.queryForObject(
        "SELECT approved_quote_id FROM t_service_order WHERE id=?",
        Long.class,
        id
      )
    ).isEqualTo(approved);
    unchanged(id, before);
    assertThat(expiry.expire(id, next, 2)).isFalse();
  }

  @Test
  void replacingExtraRecomputesFromApprovedNotPreviousTemporaryTarget()
    throws Exception {
    long id = servicing();
    var end = stamp(id, 20);
    long old = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 3)));
    var replacement = submit(
      id,
      "EXTRA",
      item(1) + "," + line("extra-2", 2, 1)
    );
    long next = q(replacement);
    assertThat(
      replacement.data().path("quote").path("addedMinutes").asInt()
    ).isEqualTo(20);
    assertThat(replacement.data().path("effectiveEnd").asString()).isEqualTo(
      end
    );
    expect(
      decide(id, old, false, 1, "2", key(), null),
      409,
      "QUOTE_VERSION_CONFLICT"
    );
    assertThat(expiry.expire(id, old, 2)).isFalse();
    confirmed(id, next);
    assertThat(expiry.expire(id, next, 3)).isFalse();
  }

  @Test
  void failedReplacementRollsBackSupersedeNewQuoteAuditAndIdempotency()
    throws Exception {
    long id = arrived(),
      old = publish(id, 1);
    var plan = bounds(id);
    String body = submission(id, "INITIAL", item(2)),
      k = key();
    rootDb().execute(
      "CREATE TRIGGER d3_fail BEFORE UPDATE ON t_idempotency FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected D3'"
    );
    try {
      expect(
        send("POST", "/" + id + "/quotes", 3, body, k),
        500,
        "INTERNAL_ERROR"
      );
    } finally {
      rootDb().execute("DROP TRIGGER d3_fail");
    }
    unchanged(id, plan);
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        old
      )
    ).isEqualTo("PENDING");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_quote WHERE order_id=?",
        Integer.class,
        id
      )
    ).isOne();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
        Integer.class,
        k
      )
    ).isZero();
    q(send("POST", "/" + id + "/quotes", 3, body, k));
  }

  @Test
  void originalConfirmationReplaysAfterStartAndNewExtra() throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    String k = key();
    var original = decide(id, q, false, 1, "1", k, null);
    expect(original, 200, null);
    expect(
      send(
        "POST",
        "/" + id + "/start",
        3,
        "{\"expectedRowVersion\":\"6\"}",
        key()
      ),
      200,
      null
    );
    q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    assertThat(decide(id, q, false, 1, "1", k, null).data()).isEqualTo(
      original.data()
    );
  }
}
