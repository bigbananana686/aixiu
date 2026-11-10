package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuoteWithdrawalApiTest extends D3Support {

  Reply withdraw(long id, long q, int version, String k) throws Exception {
    return send(
      "POST",
      "/" + id + "/quotes/" + q + "/withdraw",
      3,
      "{\"version\":\"" + version + "\"}",
      k
    );
  }

  String path(long id, long q) {
    return "/" + id + "/quotes/" + q + "/withdraw";
  }

  int version(long q) {
    return db.queryForObject(
      "SELECT version FROM t_order_quote WHERE id=?",
      Integer.class,
      q
    );
  }

  @Test
  void initialWithdrawReleasesOnlyTemporaryAndCanPublishNewRound()
    throws Exception {
    long id = arrived();
    var before = bounds(id);
    long q = publish(id, 2);
    assertThat(bounds(id)).isNotEqualTo(before);
    String k = key();
    var r = withdraw(id, q, 1, k);
    expect(r, 200, null);
    assertThat(r.data().path("quoteStatus").asString()).isEqualTo("WITHDRAWN");
    assertThat(r.data().path("state").asString()).isEqualTo("ARRIVED");
    assertThat(r.data().path("pendingQuoteId").isNull()).isTrue();
    unchanged(id, before);
    assertThat(
      db.queryForObject(
        "SELECT reservation_active FROM t_service_order WHERE id=?",
        Boolean.class,
        id
      )
    ).isTrue();
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        q
      )
    ).isEqualTo("WITHDRAWN");
    assertThat(
      get("/" + id + "/quotes", 1)
        .data()
        .path("items")
        .get(0)
        .path("status")
        .asString()
    ).isEqualTo("WITHDRAWN");
    long fresh = q(submit(id, "INITIAL", item(1)));
    assertThat(fresh).isNotEqualTo(q);
    assertThat(version(fresh)).isEqualTo(2);
    assertThat(withdraw(id, q, 1, k).data()).isEqualTo(r.data());
    assertThat(expiry.expire(id, q, 1)).isFalse();
    assertThat(
      db.queryForObject(
        "SELECT pending_quote_id FROM t_service_order WHERE id=?",
        Long.class,
        id
      )
    ).isEqualTo(fresh);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='WITHDRAW_QUOTE'",
        Integer.class,
        id
      )
    ).isOne();
    expect(withdraw(id, q, 1, key()), 409, "QUOTE_STATE_CONFLICT");
  }

  @Test
  void extraWithdrawPreservesApprovalAndMakesReleasedSlotBookable()
    throws Exception {
    long id = servicing();
    var before = bounds(id);
    long approved = db.queryForObject(
      "SELECT approved_quote_id FROM t_service_order WHERE id=?",
      Long.class,
      id
    );
    long q = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    expect(withdraw(id, q, 2, key()), 200, null);
    unchanged(id, before);
    assertThat(
      db.queryForObject(
        "SELECT approved_quote_id FROM t_service_order WHERE id=?",
        Long.class,
        id
      )
    ).isEqualTo(approved);
    assertThat(
      db.queryForObject(
        "SELECT state FROM t_service_order WHERE id=?",
        String.class,
        id
      )
    ).isEqualTo("SERVICING");
    create(0, "06:45");
    noOverlap();
    expect(
      decide(id, q, false, 1, "2", key(), null),
      409,
      "QUOTE_STATE_CONFLICT"
    );
    assertThat(expiry.expire(id, q, 2)).isFalse();
    unchanged(id, before);
  }

  @ParameterizedTest
  @ValueSource(strings = { "confirm", "reject", "expire", "replace" })
  void terminalOrSupersededQuoteCannotBeWithdrawn(String operation)
    throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    String code;
    switch (operation) {
      case "confirm" -> {
        confirmed(id, q);
        code = "QUOTE_STATE_CONFLICT";
      }
      case "reject" -> {
        expect(decide(id, q, true, 1, "1", key(), "no"), 200, null);
        code = "QUOTE_STATE_CONFLICT";
      }
      case "expire" -> {
        db.update(
          "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
          q
        );
        assertThat(expiry.expire(id, q, 1)).isTrue();
        code = "QUOTE_EXPIRED";
      }
      default -> {
        q(submit(id, "INITIAL", item(2)));
        code = "QUOTE_VERSION_CONFLICT";
      }
    }
    var before = bounds(id);
    expect(withdraw(id, q, 1, key()), 409, code);
    unchanged(id, before);
  }

  @Test
  void lateWithdrawReturnsExpiredAndImmediatelyAttemptsExpiryRelease()
    throws Exception {
    long id = servicing();
    var before = bounds(id);
    long q = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    String k = key();
    db.update(
      "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
      q
    );
    expect(withdraw(id, q, 2, k), 409, "QUOTE_EXPIRED");
    unchanged(id, before);
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        q
      )
    ).isEqualTo("EXPIRED");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
        Integer.class,
        k
      )
    ).isZero();
  }

  @Test
  void versionOwnershipNestedResourceAndCurrentAccountAreChecked()
    throws Exception {
    long id = arrived(),
      q = publish(id, 1),
      other = arrived(1),
      otherQ = publish(other, 1);
    expect(withdraw(id, q, 2, key()), 409, "QUOTE_VERSION_CONFLICT");
    expect(
      send("POST", path(id, q), 4, "{\"version\":\"1\"}", key()),
      404,
      "RESOURCE_NOT_FOUND"
    );
    expect(withdraw(id, otherQ, 1, key()), 404, "RESOURCE_NOT_FOUND");
    String k = key();
    expect(withdraw(id, q, 1, k), 200, null);
    db.update("UPDATE t_user SET enabled=0 WHERE id=3");
    try {
      expect(withdraw(id, q, 1, k), 401, "UNAUTHENTICATED");
    } finally {
      db.update("UPDATE t_user SET enabled=1 WHERE id=3");
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{broken",
      "{}",
      "{\"version\":null}",
      "{\"version\":1}",
      "{\"version\":\"01\"}",
      "{\"version\":\"1\",\"reason\":\"x\"}",
    }
  )
  void bodyValidationAndRolePrecedence(String body) throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    expect(send("POST", path(id, q), 1, body, key()), 403, "FORBIDDEN");
    expect(send("POST", path(id, q), 5, body, key()), 403, "FORBIDDEN");
    expect(send("POST", path(id, q), 3, body, key()), 400, "INVALID_ARGUMENT");
  }

  @Test
  void requiredKeyAndConflictingPayloadAreEnforced() throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    expect(withdraw(id, q, 1, null), 400, "INVALID_ARGUMENT");
    expect(withdraw(id, q, 1, "short"), 400, "INVALID_ARGUMENT");
    String k = key();
    expect(withdraw(id, q, 1, k), 200, null);
    expect(withdraw(id, q, 2, k), 409, "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void auditFailureRollsBackWithdrawalReservationAndSuccessRecord()
    throws Exception {
    long id = arrived(),
      q = publish(id, 2);
    var before = bounds(id);
    String k = key();
    rootDb().execute(
      "CREATE TRIGGER withdraw_fail BEFORE UPDATE ON t_idempotency FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected withdrawal rollback'"
    );
    try {
      expect(withdraw(id, q, 1, k), 500, "INTERNAL_ERROR");
    } finally {
      rootDb().execute("DROP TRIGGER withdraw_fail");
    }
    unchanged(id, before);
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        q
      )
    ).isEqualTo("PENDING");
    assertThat(
      db.queryForObject(
        "SELECT pending_quote_id FROM t_service_order WHERE id=?",
        Long.class,
        id
      )
    ).isEqualTo(q);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
        Integer.class,
        k
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='WITHDRAW_QUOTE'",
        Integer.class,
        id
      )
    ).isZero();
    expect(withdraw(id, q, 1, k), 200, null);
  }

  @Test
  void simultaneousSameKeyWithdrawalsCommitOnceAndReplay() throws Exception {
    long id = arrived(),
      q = publish(id, 2);
    String k = key();
    var rs = race(
      () -> unchecked("POST", path(id, q), 3, "{\"version\":\"1\"}", k),
      () -> unchecked("POST", path(id, q), 3, "{\"version\":\"1\"}", k)
    );
    assertThat(rs).allSatisfy(r -> expect(r, 200, null));
    assertThat(rs.get(0).data()).isEqualTo(rs.get(1).data());
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='WITHDRAW_QUOTE'",
        Integer.class,
        id
      )
    ).isOne();
  }

  @Test
  void withdrawRacingConfirmationHasExactlyOneWinner() throws Exception {
    long id = servicing();
    long q = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    var rs = race(
      () -> unchecked("POST", path(id, q), 3, "{\"version\":\"2\"}", key()),
      () ->
        unchecked(
          "POST",
          "/" + id + "/quotes/" + q + "/confirm",
          1,
          "{\"version\":\"2\"}",
          key()
        )
    );
    assertThat(
      rs.stream().map(Reply::status).sorted().toList()
    ).containsExactly(200, 409);
    var status = db.queryForObject(
      "SELECT status FROM t_order_quote WHERE id=?",
      String.class,
      q
    );
    assertThat(status).isIn("WITHDRAWN", "CONFIRMED");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND quote_id=? AND action IN ('WITHDRAW_QUOTE','CONFIRM_QUOTE')",
        Integer.class,
        id,
        q
      )
    ).isOne();
    noOverlap();
  }

  @Test
  void withdrawRacingReplacementCannotTouchNewVersion() throws Exception {
    long id = arrived(),
      q = publish(id, 2);
    String replacement = submission(id, "INITIAL", item(3));
    var rs = race(
      () -> unchecked("POST", path(id, q), 3, "{\"version\":\"1\"}", key()),
      () -> unchecked("POST", "/" + id + "/quotes", 3, replacement, key())
    );
    assertThat(
      rs
        .stream()
        .filter(r -> r.status() < 300)
        .count()
    ).isOne();
    assertThat(
      rs
        .stream()
        .filter(r -> r.status() == 409)
        .count()
    ).isOne();
    var before = bounds(id);
    assertThat(expiry.expire(id, q, 1)).isFalse();
    unchanged(id, before);
    noOverlap();
  }

  @Test
  void withdrawRacingExpiryProducesOneTerminalTransition() throws Exception {
    long id = servicing();
    var before = bounds(id);
    long q = q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    db.update(
      "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
      q
    );
    var rs = race(
      () -> unchecked("POST", path(id, q), 3, "{\"version\":\"2\"}", key()),
      () -> {
        expiry.expire(id, q, 2);
        return (Reply) null;
      }
    );
    expect(rs.get(0), 409, "QUOTE_EXPIRED");
    unchanged(id, before);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND quote_id=? AND action IN ('WITHDRAW_QUOTE','EXPIRE_QUOTE')",
        Integer.class,
        id,
        q
      )
    ).isOne();
  }
}
