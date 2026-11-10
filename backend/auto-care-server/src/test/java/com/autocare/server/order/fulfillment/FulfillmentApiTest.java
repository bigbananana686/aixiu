package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FulfillmentApiTest extends D2OrderSupport {

  long ready() throws Exception {
    long id = arrived();
    long q = publish(id, 1);
    expect(decide(id, q, false, 1, "1", key(), null), 200, null);
    return id;
  }

  Reply action(long id, String action, long actor, String version, String k)
    throws Exception {
    return send(
      "POST",
      "/" + id + "/" + action,
      actor,
      "{\"expectedRowVersion\":\"" + version + "\"}",
      k
    );
  }

  Reply complete(long id, String time, String extra, String k)
    throws Exception {
    return send(
      "POST",
      "/" + id + "/complete",
      3,
      "{\"expectedRowVersion\":\"7\",\"actualFinish\":\"" +
        time +
        "\",\"serviceResult\":\"done\"" +
        extra +
        "}",
      k
    );
  }

  @Test
  void fullLifecyclePreservesPlanAndReplaysAfterClose() throws Exception {
    long id = ready();
    var plan = bounds(id);
    String sk = key();
    Reply started = action(id, "start", 3, "6", sk);
    expect(started, 200, null);
    String finish = started.data().path("actualStart").asString();
    String ck = key();
    Reply completed = complete(id, finish, ",\"mileage\":23456", ck);
    expect(completed, 200, null);
    assertThat(completed.data().path("mileageSnapshot").asInt()).isEqualTo(
      23456
    );
    assertThat(completed.data().path("completedAt").asString()).isEqualTo(
      finish
    );
    assertThat(
      db.queryForObject(
        "SELECT action FROM t_order_state_log WHERE order_id=? AND to_state='SERVICING'",
        String.class,
        id
      )
    ).isEqualTo("START_ORDER");
    String closeKey = key();
    Reply closed = action(id, "close", 1, "8", closeKey);
    expect(closed, 200, null);
    assertThat(closed.data().path("reservationActive").asBoolean()).isTrue();
    assertThat(bounds(id)).isEqualTo(plan);
    assertThat(action(id, "start", 3, "6", sk).data()).isEqualTo(
      started.data()
    );
    assertThat(complete(id, finish, ",\"mileage\":23456", ck).data()).isEqualTo(
      completed.data()
    );
    assertThat(action(id, "close", 1, "8", closeKey).data()).isEqualTo(
      closed.data()
    );
    assertThat(
      get("/" + id, 1)
        .data()
        .path("serviceResult")
        .asString()
    ).isEqualTo("done");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_state_log WHERE order_id=?",
        Integer.class,
        id
      )
    ).isEqualTo(9);
  }

  @Test
  void insufficientTimeLeavesOrderAndIdempotencyUntouched() throws Exception {
    long id = ready();
    db.update(
      "UPDATE t_service_order SET service_start=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR,occupied_start=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,committed_end=UTC_TIMESTAMP(3)+INTERVAL 59 MINUTE,effective_end=committed_end WHERE id=?",
      id
    );
    expect(
      action(id, "start", 3, "6", key()),
      409,
      "SERVICE_TIME_INSUFFICIENT"
    );
    assertThat(
      get("/" + id, 1)
        .data()
        .path("state")
        .asString()
    ).isEqualTo("READY_FOR_SERVICE");
  }

  @Test
  void approvedSnapshotSurvivesCatalogDisableAndRuleChanges() throws Exception {
    long id = ready();
    db.update(
      "UPDATE t_service_item SET enabled=0,duration_minutes=1440 WHERE id=1"
    );
    try {
      expect(action(id, "start", 3, "6", key()), 200, null);
    } finally {
      db.update(
        "UPDATE t_service_item SET enabled=1,duration_minutes=45 WHERE id=1"
      );
    }
  }

  @Test
  void missingApprovalAndPendingCannotStartOrFinish() throws Exception {
    long id = arrived();
    expect(action(id, "start", 3, "4", key()), 409, "ORDER_STATE_CONFLICT");
    long q = publish(id, 1);
    expect(action(id, "complete", 3, "5", key()), 400, "INVALID_ARGUMENT");
    expect(action(id, "start", 3, "5", key()), 409, "ORDER_STATE_CONFLICT");
    assertThat(q).isPositive();
  }

  @ParameterizedTest
  @ValueSource(strings = { "start", "complete", "close" })
  void roleAndObjectOwnershipAreCheckedBeforeReplay(String action)
    throws Exception {
    long id = ready();
    long right = action.equals("close") ? 1 : 3;
    long wrong = right == 1 ? 3 : 1;
    expect(
      send("POST", "/" + id + "/" + action, wrong, "{broken", key()),
      403,
      "FORBIDDEN"
    );
    String body = action.equals("complete")
      ? "{\"expectedRowVersion\":\"6\",\"actualFinish\":\"2026-01-01T00:00:00Z\",\"serviceResult\":\"done\"}"
      : "{\"expectedRowVersion\":\"6\"}";
    expect(
      send("POST", "/" + id + "/" + action, right + 1, body, key()),
      404,
      "RESOURCE_NOT_FOUND"
    );
  }

  @Test
  void versionConflictAndConcurrentStartProduceOneTransition()
    throws Exception {
    long id = ready();
    expect(action(id, "start", 3, "5", key()), 409, "ORDER_VERSION_CONFLICT");
    var rs = race(
      () ->
        unchecked(
          "POST",
          "/" + id + "/start",
          3,
          "{\"expectedRowVersion\":\"6\"}",
          key()
        ),
      () ->
        unchecked(
          "POST",
          "/" + id + "/start",
          3,
          "{\"expectedRowVersion\":\"6\"}",
          key()
        )
    );
    assertThat(
      rs.stream().map(Reply::status).sorted().toList()
    ).containsExactly(200, 409);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_state_log WHERE order_id=? AND to_state='SERVICING'",
        Integer.class,
        id
      )
    ).isOne();
  }

  @ParameterizedTest
  @ValueSource(strings = { "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z" })
  void invalidActualTimeRollsBack(String finish) throws Exception {
    long id = ready();
    expect(action(id, "start", 3, "6", key()), 200, null);
    expect(complete(id, finish, "", key()), 400, "INVALID_ARGUMENT");
    assertThat(
      db.queryForObject(
        "SELECT actual_finish FROM t_service_order WHERE id=?",
        LocalDateTime.class,
        id
      )
    ).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = { "null", "-1", "10000000", "1.2", "\"10\"" })
  void malformedMileageIsRejected(String mileage) throws Exception {
    long id = ready();
    var started = action(id, "start", 3, "6", key());
    expect(started, 200, null);
    expect(
      complete(
        id,
        started.data().path("actualStart").asString(),
        ",\"mileage\":" + mileage,
        key()
      ),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @Test
  void omittedMileageKeepsSnapshotAndOffsetTimeNormalizesForReplay()
    throws Exception {
    long id = ready();
    Reply started = action(id, "start", 3, "6", key());
    expect(started, 200, null);
    String utc = started.data().path("actualStart").asString();
    String k = key();
    Reply first = complete(id, utc, "", k);
    expect(first, 200, null);
    String offset = Instant.parse(utc)
      .atOffset(ZoneOffset.ofHours(8))
      .toString();
    // OffsetDateTime.toString may omit seconds; use an explicit RFC3339 formatter.
    offset = Instant.parse(utc)
      .atOffset(ZoneOffset.ofHours(8))
      .format(
        java.time.format.DateTimeFormatter.ofPattern(
          "uuuu-MM-dd'T'HH:mm:ss.SSSXXX"
        )
      );
    assertThat(complete(id, offset, "", k).data()).isEqualTo(first.data());
    assertThat(first.data().path("mileageSnapshot").asInt()).isEqualTo(35000);
    expect(action(id, "close", 3, "8", key()), 403, "FORBIDDEN");
  }

  @Test
  void auditFailureRollsBackActualStateAndSuccessRecord() throws Exception {
    long id = ready();
    String k = key();
    rootDb().execute(
      "CREATE TRIGGER d2_fail_audit BEFORE INSERT ON t_order_operation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='d2 injected'"
    );
    try {
      expect(action(id, "start", 3, "6", k), 500, "INTERNAL_ERROR");
    } finally {
      rootDb().execute("DROP TRIGGER d2_fail_audit");
    }
    assertThat(
      get("/" + id, 1)
        .data()
        .path("state")
        .asString()
    ).isEqualTo("READY_FOR_SERVICE");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
        Integer.class,
        k
      )
    ).isZero();
    expect(action(id, "start", 3, "6", k), 200, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  FulfillmentService fulfillment;

  @ParameterizedTest
  @ValueSource(ints = { -1, 0, 1 })
  void exactLatestStartUsesLockedDatabaseTime(int offsetMillis)
    throws Exception {
    long id = ready();
    String k = key();
    LocalDateTime latest = db.queryForObject(
      "SELECT committed_end-INTERVAL 60 MINUTE FROM t_service_order WHERE id=?",
      LocalDateTime.class,
      id
    );
    LocalDateTime now = latest.plusNanos(offsetMillis * 1000000L);
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
      new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
          .header("alg", "HS256")
          .subject("3")
          .build()
      )
    );
    var tx = new org.springframework.transaction.support.TransactionTemplate(
      manager
    );
    java.util.function.Supplier<Object> run = () ->
      tx.execute(status -> {
        try {
          db.execute(
            "SET timestamp=" +
              java.math.BigDecimal.valueOf(
                now.toInstant(ZoneOffset.UTC).toEpochMilli(),
                3
              ).toPlainString()
          );
          assertThat(
            db.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class)
          ).isEqualTo(now);
          System.out.println(
            "D2 START_BOUNDARY latest=" +
              latest +
              " dbNow=" +
              now +
              " offsetMs=" +
              offsetMillis
          );
          return fulfillment.execute(
            3,
            String.valueOf(id),
            "start",
            json.readTree("{\"expectedRowVersion\":\"6\"}"),
            k,
            UUID.randomUUID().toString()
          );
        } finally {
          db.execute("SET timestamp=DEFAULT");
        }
      });
    if (offsetMillis <= 0) {
      run.get();
      assertThat(
        db.queryForObject(
          "SELECT actual_start FROM t_service_order WHERE id=?",
          LocalDateTime.class,
          id
        )
      ).isEqualTo(now);
    } else {
      assertThatThrownBy(run::get).isInstanceOfSatisfying(
        com.autocare.common.error.ApiException.class,
        e ->
          assertThat(e.errorCode()).isEqualTo(
            com.autocare.common.error.ErrorCode.SERVICE_TIME_INSUFFICIENT
          )
      );
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
          Integer.class,
          k
        )
      ).isZero();
    }
  }
}
