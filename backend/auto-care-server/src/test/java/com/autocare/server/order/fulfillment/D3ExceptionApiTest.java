package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class D3ExceptionApiTest extends D3Support {

  @Test
  void extensionPreservesApprovalStateAndLogsOnlyOperationAndReplays()
    throws Exception {
    long id = ready();
    long approval = db.queryForObject(
      "SELECT approved_quote_id FROM t_service_order WHERE id=?",
      Long.class,
      id
    );
    int logs = db.queryForObject(
      "SELECT COUNT(*) FROM t_order_state_log WHERE order_id=?",
      Integer.class,
      id
    );
    String end = stamp(id, 20),
      body = extension(id, end),
      k = key();
    var r = send("POST", "/" + id + "/extension", 3, body, k);
    expect(r, 200, null);
    assertThat(r.data().path("previousCommittedEnd").asString()).isNotEqualTo(
      end
    );
    assertThat(r.data().path("committedEnd").asString()).isEqualTo(end);
    assertThat(
      db.queryForObject(
        "SELECT approved_quote_id FROM t_service_order WHERE id=?",
        Long.class,
        id
      )
    ).isEqualTo(approval);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_state_log WHERE order_id=?",
        Integer.class,
        id
      )
    ).isEqualTo(logs);
    assertThat(
      send("POST", "/" + id + "/extension", 3, body, k).data()
    ).isEqualTo(r.data());
    expect(
      send(
        "POST",
        "/" + id + "/extension",
        3,
        body.replace(end, stamp(id, 20)),
        k
      ),
      409,
      "IDEMPOTENCY_KEY_REUSED"
    );
  }

  @Test
  void conflictRollsBackCommandThenIndependentlyRecordsAndSameKeyCanRetry()
    throws Exception {
    long id = ready(),
      other = create(0, "07:00");
    var plan = bounds(id);
    String body = extension(id, stamp(id, 90)),
      k = key();
    var r = send("POST", "/" + id + "/extension", 3, body, k);
    expect(r, 409, "SLOT_CONFLICT");
    assertThat(
      r
        .body()
        .path("error")
        .path("details")
        .path("exceptionRecorded")
        .asBoolean()
    ).isTrue();
    unchanged(id, plan);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
        Integer.class,
        k
      )
    ).isZero();
    var detail = get("/" + id, 1)
      .data()
      .path("exceptions");
    assertThat(detail.size()).isOne();
    assertThat(detail.get(0).propertyNames()).containsExactlyInAnyOrder(
      "exceptionId",
      "type",
      "orderId",
      "requestId",
      "requestedTargetEnd",
      "recordedAt",
      "resolved"
    );
    var list = get(
      "/api/v1/admin/order-exceptions?orderId=" +
        id +
        "&resolved=false&type=DEFERRAL_SLOT_CONFLICT",
      5
    );
    expect(list, 200, null);
    assertThat(list.data().path("items")).isEqualTo(detail);
    expect(
      send(
        "POST",
        "/" + other + "/cancel",
        1,
        "{\"expectedRowVersion\":\"1\"}",
        key()
      ),
      200,
      null
    );
    expect(send("POST", "/" + id + "/extension", 3, body, k), 200, null);
    noOverlap();
  }

  @Test
  void exceptionWriteFailureStillReturnsConflictAndFalse() throws Exception {
    long id = ready();
    create(0, "07:00");
    var before = bounds(id);
    rootDb().execute(
      "CREATE TRIGGER d3_exception_fail BEFORE INSERT ON t_order_exception FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected exception'"
    );
    try {
      var r = extend(id, stamp(id, 90), key());
      expect(r, 409, "SLOT_CONFLICT");
      assertThat(
        r
          .body()
          .path("error")
          .path("details")
          .path("exceptionRecorded")
          .asBoolean()
      ).isFalse();
    } finally {
      rootDb().execute("DROP TRIGGER d3_exception_fail");
    }
    unchanged(id, before);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_exception WHERE order_id=?",
        Integer.class,
        id
      )
    ).isZero();
  }

  @Test
  void pendingPreventsExtensionAndInvalidTargetsDoNotRecordExceptions()
    throws Exception {
    long id = servicing();
    expect(extend(id, stamp(id, 0), key()), 400, "INVALID_ARGUMENT");
    expect(extend(id, stamp(id, -1), key()), 400, "INVALID_ARGUMENT");
    expect(extend(id, stamp(id, 1000), key()), 409, "WORK_WINDOW_VIOLATION");
    q(submit(id, "EXTRA", item(1) + "," + line("extra-1", 2, 1)));
    expect(extend(id, stamp(id, 30), key()), 409, "ORDER_STATE_CONFLICT");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_exception WHERE order_id=?",
        Integer.class,
        id
      )
    ).isZero();
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{broken",
      "{}",
      "{\"expectedRowVersion\":null}",
      "{\"expectedRowVersion\":\"6\",\"targetEnd\":\"bad\",\"reason\":\"x\"}",
    }
  )
  void extensionValidationAndRolePrecedence(String b) throws Exception {
    long id = ready();
    expect(
      send("POST", "/" + id + "/extension", 1, b, key()),
      403,
      "FORBIDDEN"
    );
    expect(
      send("POST", "/" + id + "/extension", 3, b, key()),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      send(
        "POST",
        "/" + id + "/extension",
        4,
        extension(id, stamp(id, 10)),
        key()
      ),
      404,
      "RESOURCE_NOT_FOUND"
    );
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "page=0",
      "pageSize=101",
      "page=1&page=2",
      "type=BAD",
      "resolved=1",
      "orderId=01",
      "from=bad",
      "sort=id",
      "from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z",
    }
  )
  void adminExceptionQueryValidationAndAuthorization(String query)
    throws Exception {
    expect(get("/api/v1/admin/order-exceptions?" + query, 1), 403, "FORBIDDEN");
    expect(
      get("/api/v1/admin/order-exceptions?" + query, 5),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @org.springframework.beans.factory.annotation.Autowired
  com.autocare.server.order.exception.OrderExceptionService exceptions;

  @Test
  void deferralDeduplicatesByOrderRequestAndType() throws Exception {
    long id = ready();
    String request = UUID.randomUUID().toString();
    LocalDateTime end = db
      .queryForObject(
        "SELECT committed_end FROM t_service_order WHERE id=?",
        LocalDateTime.class,
        id
      )
      .plusMinutes(10);
    assertThat(exceptions.deferral(id, request, end)).isTrue();
    assertThat(exceptions.deferral(id, request, end)).isTrue();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_exception WHERE order_id=?",
        Integer.class,
        id
      )
    ).isOne();
  }

  @Test
  void overPlanCompletionIsAtomicPreservesPlanAndReplays() throws Exception {
    long id = servicing();
    db.update(
      "UPDATE t_service_order SET actual_start=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,service_start=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,occupied_start=UTC_TIMESTAMP(3)-INTERVAL 3 HOUR,committed_end=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR,effective_end=committed_end WHERE id=?",
      id
    );
    var before = bounds(id);
    String finish = com.autocare.server.web.UtcTimes.format(
      db.queryForObject(
        "SELECT committed_end+INTERVAL 20 MINUTE FROM t_service_order WHERE id=?",
        LocalDateTime.class,
        id
      )
    );
    String body =
        "{\"expectedRowVersion\":\"7\",\"actualFinish\":\"" +
        finish +
        "\",\"serviceResult\":\"done\"}",
      k = key();
    rootDb().execute(
      "CREATE TRIGGER d3_fail_audit BEFORE INSERT ON t_order_operation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'"
    );
    try {
      expect(
        send("POST", "/" + id + "/complete", 3, body, k),
        500,
        "INTERNAL_ERROR"
      );
    } finally {
      rootDb().execute("DROP TRIGGER d3_fail_audit");
    }
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_exception WHERE order_id=?",
        Integer.class,
        id
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT state FROM t_service_order WHERE id=?",
        String.class,
        id
      )
    ).isEqualTo("SERVICING");
    var r = send("POST", "/" + id + "/complete", 3, body, k);
    expect(r, 200, null);
    unchanged(id, before);
    assertThat(
      r.data().path("overPlan").path("overPlanMinutes").asInt()
    ).isEqualTo(20);
    assertThat(
      send("POST", "/" + id + "/complete", 3, body, k).data()
    ).isEqualTo(r.data());
    assertThat(
      get("/" + id, 1)
        .data()
        .path("exceptions")
        .size()
    ).isOne();
  }
}
