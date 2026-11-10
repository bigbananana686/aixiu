package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceManagementApiTest extends D2OrderSupport {

  List<Map<String, Object>> originalWindows;
  int originalScheduleVersion;

  void backupWindows() {
    if (originalWindows != null) return;
    originalWindows = db.queryForList(
      "SELECT * FROM t_technician_window WHERE technician_id=3"
    );
    originalScheduleVersion = db.queryForObject(
      "SELECT schedule_version FROM t_technician WHERE user_id=3",
      Integer.class
    );
  }

  List<Long> catalogIds = new ArrayList<>();
  String catalog = "/api/v1/admin/service-items";

  @AfterEach
  void cleanupResources() {
    if (originalWindows != null) {
      db.update("DELETE FROM t_technician_window WHERE technician_id=3");
      for (var w : originalWindows)
        db.update(
          "INSERT INTO t_technician_window(technician_id,window_start,window_end,created_at,updated_at) VALUES(3,?,?,?,?)",
          w.get("window_start"),
          w.get("window_end"),
          w.get("created_at"),
          w.get("updated_at")
        );
      db.update(
        "UPDATE t_technician SET schedule_version=? WHERE user_id=3",
        originalScheduleVersion
      );
    }
    for (long id : catalogIds)
      db.update("DELETE FROM t_service_item WHERE id=?", id);
  }

  String catalogBody(String code) {
    return (
      "{\"code\":\"" +
      code +
      "\",\"name\":\"d2 item\",\"durationMinutes\":25,\"unitPrice\":\"99.00\"}"
    );
  }

  Reply newCatalog(String key) throws Exception {
    Reply r = send(
      "POST",
      catalog,
      5,
      catalogBody(
        "D2_" +
          UUID.randomUUID()
            .toString()
            .replace("-", "")
            .substring(0, 20)
            .toUpperCase()
      ),
      key
    );
    expect(r, 201, null);
    catalogIds.add(r.data().path("serviceItemId").asLong());
    return r;
  }

  @Test
  void catalogCreatePatchDisableAndReplay() throws Exception {
    String k = key();
    Reply created = newCatalog(k);
    long id = created.data().path("serviceItemId").asLong();
    String code = created.data().path("code").asString();
    assertThat(created.data().path("catalogVersion").asString()).isEqualTo("1");
    assertThat(created.data().path("enabled").asBoolean()).isTrue();
    assertThat(created.response().headers().firstValue("Location")).contains(
      catalog + "/" + id
    );
    String pk = key(),
      body =
        "{\"expectedCatalogVersion\":\"1\",\"enabled\":false,\"unitPrice\":\"109.00\"}";
    Reply changed = send("PATCH", catalog + "/" + id, 5, body, pk);
    expect(changed, 200, null);
    assertThat(changed.data().path("catalogVersion").asString()).isEqualTo("2");
    assertThat(send("PATCH", catalog + "/" + id, 5, body, pk).data()).isEqualTo(
      changed.data()
    );
    assertThat(send("POST", catalog, 5, catalogBody(code), k).data()).isEqualTo(
      created.data()
    );
    expect(
      send("POST", catalog, 5, catalogBody(code), key()),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      send(
        "PATCH",
        catalog + "/" + id,
        5,
        "{\"expectedCatalogVersion\":\"1\",\"name\":\"stale\"}",
        key()
      ),
      409,
      "CATALOG_VERSION_CONFLICT"
    );
    Reply listed = send("GET", catalog + "?enabled=false", 5, null, null);
    expect(listed, 200, null);
    assertThat(listed.data().path("items").toString()).contains("d2 item");
    Reply publicItems = send("GET", "/api/v1/service-items", 1, null, null);
    expect(publicItems, 200, null);
    assertThat(publicItems.data().path("items").toString()).doesNotContain(
      code
    );
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{\"expectedCatalogVersion\":\"1\"}",
      "{\"expectedCatalogVersion\":\"1\",\"name\":null}",
      "{\"expectedCatalogVersion\":1,\"enabled\":false}",
      "{\"expectedCatalogVersion\":\"1\",\"unitPrice\":\"01.00\"}",
      "{\"expectedCatalogVersion\":\"1\",\"durationMinutes\":0}",
      "{\"expectedCatalogVersion\":\"1\",\"code\":\"CHANGED\"}",
    }
  )
  void invalidCatalogPatchIsRejected(String body) throws Exception {
    long id = newCatalog(key()).data().path("serviceItemId").asLong();
    expect(
      send("PATCH", catalog + "/" + id, 5, body, key()),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @Test
  void catalogRolesPaginationAndNullResourceReplay() throws Exception {
    expect(send("POST", catalog, 1, "{broken", key()), 403, "FORBIDDEN");
    expect(
      send("GET", catalog + "?pageSize=101", 5, null, null),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      send("GET", catalog + "?enabled=1", 5, null, null),
      400,
      "INVALID_ARGUMENT"
    );
    String k = key();
    Reply r = newCatalog(k);
    long id = r.data().path("serviceItemId").asLong();
    db.update(
      "UPDATE t_idempotency SET resource_id=NULL WHERE idempotency_key=?",
      k
    );
    expect(
      send(
        "POST",
        catalog,
        5,
        catalogBody(r.data().path("code").asString()),
        k
      ),
      404,
      "RESOURCE_NOT_FOUND"
    );
    expect(
      send(
        "PATCH",
        catalog + "/9223372036854775807",
        5,
        "{\"expectedCatalogVersion\":\"1\",\"enabled\":false}",
        key()
      ),
      404,
      "RESOURCE_NOT_FOUND"
    );
    assertThat(id).isPositive();
  }

  @Test
  void concurrentCatalogVersionUpdateHasOneWinner() throws Exception {
    long id = newCatalog(key()).data().path("serviceItemId").asLong();
    String body = "{\"expectedCatalogVersion\":\"1\",\"name\":\"updated\"}";
    var rs = race(
      () -> unchecked("PATCH", catalog + "/" + id, 5, body, key()),
      () -> unchecked("PATCH", catalog + "/" + id, 5, body, key())
    );
    assertThat(
      rs.stream().map(Reply::status).sorted().toList()
    ).containsExactly(200, 409);
    assertThat(
      db.queryForObject(
        "SELECT version FROM t_service_item WHERE id=?",
        Integer.class,
        id
      )
    ).isEqualTo(2);
  }

  String windowsPath = "/api/v1/admin/technicians/3/windows";

  String windowBody(int version, String nodes) {
    return (
      "{\"rangeStart\":\"" +
      day +
      "\",\"rangeEnd\":\"" +
      day.plusDays(1) +
      "\",\"expectedScheduleVersion\":\"" +
      version +
      "\",\"windows\":" +
      nodes +
      "}"
    );
  }

  String window(String start, String end) {
    return "{\"windowStart\":\"" + start + "\",\"windowEnd\":\"" + end + "\"}";
  }

  String normalWindows() {
    return "[" + window(day + "T01:00:00Z", day + "T09:00:00Z") + "]";
  }

  @Test
  void windowReplaceClearReplayAndOutsideRangeUntouched() throws Exception {
    backupWindows();
    String k = key();
    String body = windowBody(originalScheduleVersion, "[]");
    Reply cleared = send("PUT", windowsPath, 5, body, k);
    expect(cleared, 200, null);
    assertThat(cleared.data().path("windows").isEmpty()).isTrue();
    expect(
      send(
        "PUT",
        windowsPath,
        5,
        windowBody(originalScheduleVersion, normalWindows()),
        key()
      ),
      409,
      "RESOURCE_VERSION_CONFLICT"
    );
    Reply replaced = send(
      "PUT",
      windowsPath,
      5,
      windowBody(originalScheduleVersion + 1, normalWindows()),
      key()
    );
    expect(replaced, 200, null);
    assertThat(send("PUT", windowsPath, 5, body, k).data()).isEqualTo(
      cleared.data()
    );
    Reply read = send(
      "GET",
      windowsPath + "?rangeStart=" + day + "&rangeEnd=" + day.plusDays(1),
      5,
      null,
      null
    );
    expect(read, 200, null);
    assertThat(read.data().path("windows").size()).isEqualTo(1);
    assertThat(read.data().path("scheduleVersion").asInt()).isEqualTo(
      originalScheduleVersion + 2
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_technician_window WHERE technician_id=3",
        Integer.class
      )
    ).isEqualTo(originalWindows.size());
    Reply techs = send("GET", "/api/v1/admin/technicians", 5, null, null);
    expect(techs, 200, null);
    assertThat(
      techs.data().path("items").get(0).propertyNames()
    ).containsExactlyInAnyOrder(
      "technicianId",
      "displayName",
      "enabled",
      "scheduleVersion"
    );
  }

  @Test
  void activeReservationPreventsWindowRemovalAndFailureDoesNotConsumeKey()
    throws Exception {
    backupWindows();
    long id = create(0, "05:00");
    String k = key();
    String body = windowBody(originalScheduleVersion, "[]");
    expect(send("PUT", windowsPath, 5, body, k), 409, "WORK_WINDOW_CONFLICT");
    assertThat(
      db.queryForObject(
        "SELECT schedule_version FROM t_technician WHERE user_id=3",
        Integer.class
      )
    ).isEqualTo(originalScheduleVersion);
    expect(
      send(
        "POST",
        "/" + id + "/cancel",
        1,
        "{\"expectedRowVersion\":\"1\"}",
        key()
      ),
      200,
      null
    );
    expect(send("PUT", windowsPath, 5, body, k), 200, null);
  }

  @Test
  void crossingOldWindowsPreserveBothOutsideFragments() throws Exception {
    backupWindows();
    db.update("DELETE FROM t_technician_window WHERE technician_id=3");
    db.update(
      "INSERT INTO t_technician_window(technician_id,window_start,window_end,created_at,updated_at) VALUES(3,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
      day.minusDays(1).atTime(12, 0),
      day.plusDays(1).atTime(12, 0)
    );
    expect(
      send(
        "PUT",
        windowsPath,
        5,
        windowBody(originalScheduleVersion, "[]"),
        key()
      ),
      200,
      null
    );
    var rows = db.queryForList(
      "SELECT window_start,window_end FROM t_technician_window WHERE technician_id=3 ORDER BY window_start"
    );
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("window_start")).isEqualTo(
      day.minusDays(1).atTime(12, 0)
    );
    assertThat(rows.get(1).get("window_end")).isEqualTo(
      day.plusDays(1).atTime(12, 0)
    );
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "null",
      "{}",
      "[{\"windowStart\":\"2026-01-01T00:00:00Z\",\"windowEnd\":\"2026-01-02T00:00:00Z\"}]",
    }
  )
  void invalidWindowReplacementIsRejected(String nodes) throws Exception {
    expect(
      send("PUT", windowsPath, 5, windowBody(1, nodes), key()),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @Test
  void overlappingWindowsRejectedButAdjacentAllowed() throws Exception {
    backupWindows();
    String a = window(day + "T01:00:00Z", day + "T05:00:00Z"),
      b = window(day + "T04:59:00Z", day + "T09:00:00Z");
    expect(
      send(
        "PUT",
        windowsPath,
        5,
        windowBody(originalScheduleVersion, "[" + a + "," + b + "]"),
        key()
      ),
      400,
      "INVALID_ARGUMENT"
    );
    b = window(day + "T05:00:00Z", day + "T09:00:00Z");
    expect(
      send(
        "PUT",
        windowsPath,
        5,
        windowBody(originalScheduleVersion, "[" + a + "," + b + "]"),
        key()
      ),
      200,
      null
    );
  }

  @Test
  void windowAndNewReservationCompeteUnderSameGuard() throws Exception {
    backupWindows();
    String replace = windowBody(originalScheduleVersion, "[]");
    String create =
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      day +
      "T05:00:00Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"d2 synthetic\"}";
    var rs = race(
      () -> unchecked("PUT", windowsPath, 5, replace, key()),
      () -> unchecked("POST", "", 1, create, key())
    );
    for (var r : rs)
      if (r.status() == 201) ids.add(r.data().path("orderId").asLong());
    assertThat(
      rs
        .stream()
        .filter(r -> r.status() < 300)
        .count()
    ).isEqualTo(1);
    assertThat(
      rs
        .stream()
        .filter(r -> r.status() == 409)
        .count()
    ).isEqualTo(1);
  }

  @Test
  void windowWriteFailureRollsBackDeleteInsertVersionAndKey() throws Exception {
    backupWindows();
    String k = key();
    String body = windowBody(originalScheduleVersion, normalWindows());
    rootDb().execute(
      "CREATE TRIGGER d2_fail_windows BEFORE INSERT ON t_technician_window FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='d2 window injected'"
    );
    try {
      expect(send("PUT", windowsPath, 5, body, k), 500, "INTERNAL_ERROR");
    } finally {
      rootDb().execute("DROP TRIGGER d2_fail_windows");
    }
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_technician_window WHERE technician_id=3",
        Integer.class
      )
    ).isEqualTo(originalWindows.size());
    assertThat(
      db.queryForObject(
        "SELECT schedule_version FROM t_technician WHERE user_id=3",
        Integer.class
      )
    ).isEqualTo(originalScheduleVersion);
    expect(send("PUT", windowsPath, 5, body, k), 200, null);
  }

  String rescheduleBody(String v, String dateTime) {
    return (
      "{\"expectedRowVersion\":\"" +
      v +
      "\",\"serviceStart\":\"" +
      dateTime +
      "\"}"
    );
  }

  @Test
  void reschedulePreservesDeadlineAndStateHistoryAndReplaysAfterDepart()
    throws Exception {
    long id = create(0, "05:00");
    String deadline = get("/" + id, 1)
      .data()
      .path("acceptDeadline")
      .asString();
    String k = key(),
      body = rescheduleBody("1", day + "T07:00:00Z");
    Reply changed = send("POST", "/" + id + "/reschedule", 1, body, k);
    expect(changed, 200, null);
    assertThat(changed.data().path("acceptDeadline").asString()).isEqualTo(
      deadline
    );
    assertThat(changed.data().path("occupiedStart").asString()).isEqualTo(
      day + "T06:15:00.000Z"
    );
    assertThat(changed.data().path("committedEnd").asString()).isEqualTo(
      day + "T08:00:00.000Z"
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_state_log WHERE order_id=?",
        Integer.class,
        id
      )
    ).isOne();
    expect(
      send(
        "POST",
        "/" + id + "/accept",
        3,
        "{\"expectedRowVersion\":\"2\"}",
        key()
      ),
      200,
      null
    );
    expect(
      send(
        "POST",
        "/" + id + "/depart",
        3,
        "{\"expectedRowVersion\":\"3\"}",
        key()
      ),
      200,
      null
    );
    assertThat(
      send("POST", "/" + id + "/reschedule", 1, body, k).data()
    ).isEqualTo(changed.data());
    expect(
      send(
        "POST",
        "/" + id + "/reschedule",
        1,
        rescheduleBody("4", day + "T07:00:00Z"),
        key()
      ),
      409,
      "ORDER_STATE_CONFLICT"
    );
    expect(
      send(
        "POST",
        "/" + id + "/reschedule",
        1,
        rescheduleBody("1", day + "T07:00:00Z"),
        key()
      ),
      409,
      "ORDER_VERSION_CONFLICT"
    );
  }

  @Test
  void rescheduleExcludesSelfAndAllowsTouchingButRejectsOneMinuteOverlap()
    throws Exception {
    long id = create(0, "05:00");
    create(0, "07:00");
    expect(
      send(
        "POST",
        "/" + id + "/reschedule",
        1,
        rescheduleBody("1", day + "T05:15:00Z"),
        key()
      ),
      200,
      null
    );
    var before = get("/" + id, 1).data();
    expect(
      send(
        "POST",
        "/" + id + "/reschedule",
        1,
        rescheduleBody("2", day + "T05:16:00Z"),
        key()
      ),
      409,
      "SLOT_CONFLICT"
    );
    assertThat(get("/" + id, 1).data()).isEqualTo(before);
  }

  @Test
  void rescheduleRetainsBookingSnapshotWhenCurrentCatalogChanges()
    throws Exception {
    long id = create(0, "05:00");
    var original = db.queryForMap("SELECT * FROM t_service_item WHERE id=1");
    try {
      expect(
        send(
          "PATCH",
          catalog + "/1",
          5,
          "{\"expectedCatalogVersion\":\"1\",\"unitPrice\":\"999.00\",\"durationMinutes\":120,\"enabled\":false}",
          key()
        ),
        200,
        null
      );
      Reply r = send(
        "POST",
        "/" + id + "/reschedule",
        1,
        rescheduleBody("1", day + "T07:00:00Z"),
        key()
      );
      expect(r, 200, null);
      assertThat(r.data().path("committedEnd").asString()).isEqualTo(
        day + "T08:00:00.000Z"
      );
      assertThat(
        get("/" + id, 1)
          .data()
          .path("bookingItems")
          .get(0)
          .path("unitPrice")
          .asString()
      ).isEqualTo("199.00");
    } finally {
      restoreCatalogOne(original);
    }
  }

  void restoreCatalogOne(Map<String, Object> original) {
    db.update(
      "UPDATE t_service_item SET price=?,duration_minutes=?,enabled=?,version=?,updated_at=? WHERE id=1",
      original.get("price"),
      original.get("duration_minutes"),
      original.get("enabled"),
      original.get("version"),
      original.get("updated_at")
    );
  }

  @Test
  void rescheduleAndNewOrderCannotBothTakeTargetSlot() throws Exception {
    long id = create(0, "05:00");
    String target = day + "T07:00:00Z";
    String create =
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      target +
      "\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"d2 synthetic\"}";
    var rs = race(
      () ->
        unchecked(
          "POST",
          "/" + id + "/reschedule",
          1,
          rescheduleBody("1", target),
          key()
        ),
      () -> unchecked("POST", "", 1, create, key())
    );
    for (var r : rs)
      if (r.status() == 201) ids.add(r.data().path("orderId").asLong());
    assertThat(
      rs
        .stream()
        .filter(r -> r.status() < 300)
        .count()
    ).isEqualTo(1);
    assertThat(
      rs
        .stream()
        .filter(r -> r.status() == 409)
        .count()
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_service_order a JOIN t_service_order b ON a.id<b.id AND a.technician_id=b.technician_id AND a.reservation_active=1 AND b.reservation_active=1 AND a.occupied_start<b.effective_end AND b.occupied_start<a.effective_end",
        Integer.class
      )
    ).isZero();
  }

  @Test
  void catalogUpdateRacingBookingNeverMixesVersions() throws Exception {
    var original = db.queryForMap("SELECT * FROM t_service_item WHERE id=1");
    String create =
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      day +
      "T05:00:00Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"d2 synthetic\"}";
    try {
      var rs = race(
        () ->
          unchecked(
            "PATCH",
            catalog + "/1",
            5,
            "{\"expectedCatalogVersion\":\"1\",\"unitPrice\":\"999.00\",\"durationMinutes\":120}",
            key()
          ),
        () -> unchecked("POST", "", 1, create, key())
      );
      assertThat(rs.get(0).status()).isEqualTo(200);
      Reply booked = rs.get(1);
      if (booked.status() == 201) {
        ids.add(booked.data().path("orderId").asLong());
        assertThat(
          booked.data().path("bookingItems").get(0).path("unitPrice").asString()
        ).isEqualTo("199.00");
        assertThat(booked.data().path("committedEnd").asString()).isEqualTo(
          day + "T06:00:00.000Z"
        );
      } else expect(booked, 409, "CATALOG_VERSION_CONFLICT");
    } finally {
      restoreCatalogOne(original);
    }
  }

  @Test
  void rescheduleRejectsPastOutsideAndWrongOwnerAndRollsBackOnAuditFailure()
    throws Exception {
    long id = create(0, "05:00");
    var before = get("/" + id, 1).data();
    expect(
      send(
        "POST",
        "/" + id + "/reschedule",
        2,
        rescheduleBody("1", day + "T07:00:00Z"),
        key()
      ),
      404,
      "RESOURCE_NOT_FOUND"
    );
    expect(
      send("POST", "/" + id + "/reschedule", 3, "{broken", key()),
      403,
      "FORBIDDEN"
    );
    expect(
      send(
        "POST",
        "/" + id + "/reschedule",
        1,
        rescheduleBody("1", "2020-01-01T05:00:00Z"),
        key()
      ),
      409,
      "WORK_WINDOW_VIOLATION"
    );
    expect(
      send(
        "POST",
        "/" + id + "/reschedule",
        1,
        rescheduleBody("1", day + "T23:00:00Z"),
        key()
      ),
      409,
      "WORK_WINDOW_VIOLATION"
    );
    String k = key(),
      body = rescheduleBody("1", day + "T07:00:00Z");
    rootDb().execute(
      "CREATE TRIGGER d2_reschedule_fail BEFORE INSERT ON t_order_operation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='d2 reschedule injected'"
    );
    try {
      expect(
        send("POST", "/" + id + "/reschedule", 1, body, k),
        500,
        "INTERNAL_ERROR"
      );
    } finally {
      rootDb().execute("DROP TRIGGER d2_reschedule_fail");
    }
    assertThat(get("/" + id, 1).data()).isEqualTo(before);
    expect(send("POST", "/" + id + "/reschedule", 1, body, k), 200, null);
  }

  @Test
  void twentyIndependentRequestsForSameSlotProduceOneReservation()
    throws Exception {
    String body =
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      day +
      "T05:00:00Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"d2 concurrency synthetic\"}";
    var ready = new java.util.concurrent.CountDownLatch(20);
    var go = new java.util.concurrent.CountDownLatch(1);
    List<Reply> replies = new ArrayList<>();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(20)) {
      List<java.util.concurrent.Future<Reply>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        String k = key();
        futures.add(
          pool.submit(() -> {
            ready.countDown();
            if (
              !go.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ) throw new IllegalStateException("barrier");
            return unchecked("POST", "", 1, body, k);
          })
        );
      }
      assertThat(
        ready.await(5, java.util.concurrent.TimeUnit.SECONDS)
      ).isTrue();
      go.countDown();
      for (var f : futures) {
        Reply r = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        replies.add(r);
        if (r.status() == 201) ids.add(r.data().path("orderId").asLong());
      }
    }
    assertThat(
      replies
        .stream()
        .filter(r -> r.status() == 201)
        .count()
    ).isEqualTo(1);
    for (var r : replies)
      if (r.status() != 201) expect(r, 409, "SLOT_CONFLICT");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_service_order WHERE technician_id=3 AND reservation_active=1",
        Integer.class
      )
    ).isOne();
  }

  @Test
  void catalogUpdateRacingInitialQuoteNeverMixesAmountAndDuration()
    throws Exception {
    long id = arrived();
    var original = db.queryForMap("SELECT * FROM t_service_item WHERE id=1");
    try {
      var rs = race(
        () ->
          unchecked(
            "PATCH",
            catalog + "/1",
            5,
            "{\"expectedCatalogVersion\":\"1\",\"unitPrice\":\"999.00\",\"durationMinutes\":120}",
            key()
          ),
        () -> unchecked("POST", "/" + id + "/quotes", 3, body(1, "4"), key())
      );
      expect(rs.get(0), 200, null);
      Reply published = rs.get(1);
      if (published.status() == 201) {
        var q = published.data().path("quote");
        assertThat(q.path("totalAmount").asString()).isEqualTo("199.00");
        assertThat(q.path("totalMinutes").asInt()).isEqualTo(45);
        assertThat(q.path("targetEnd").asString()).isEqualTo(
          day + "T06:00:00.000Z"
        );
      } else expect(published, 409, "CATALOG_VERSION_CONFLICT");
    } finally {
      restoreCatalogOne(original);
    }
  }
}
