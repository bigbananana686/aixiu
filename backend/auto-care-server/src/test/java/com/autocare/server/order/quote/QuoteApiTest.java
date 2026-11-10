package com.autocare.server.order.quote;

import static org.assertj.core.api.Assertions.*;

import com.autocare.common.identity.Role;
import com.autocare.server.security.JwtTokenIssuer;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "autocare.order.accept-expiry-enabled=false",
    "autocare.order.quote-expiry-enabled=false",
  }
)
class QuoteApiTest {

  @LocalServerPort
  int port;

  @Autowired
  JdbcTemplate db;

  @Autowired
  ObjectMapper json;

  @Autowired
  JwtTokenIssuer issuer;

  @Autowired
  QuoteExpiryService expiry;

  @Autowired
  QuoteService service;

  @Autowired
  PlatformTransactionManager manager;

  List<Long> ids = new ArrayList<>();
  List<String> keys = Collections.synchronizedList(new ArrayList<>());
  HttpClient client = HttpClient.newHttpClient();
  LocalDate day;

  @BeforeEach
  void setup() {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class)).startsWith(
      "auto_care_g104_"
    );
    day = db.queryForObject(
      "SELECT DATE(MIN(window_start)) FROM t_technician_window WHERE technician_id=3 AND window_start>UTC_TIMESTAMP(3)",
      LocalDate.class
    );
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    db.execute("DROP TRIGGER IF EXISTS d1_fail_success");
    for (long id : ids) {
      db.update(
        "UPDATE t_service_order SET approved_quote_id=NULL,pending_quote_id=NULL WHERE id=?",
        id
      );
      db.update("DELETE FROM t_order_operation WHERE order_id=?", id);
      db.update("DELETE FROM t_order_state_log WHERE order_id=?", id);
      db.update(
        "DELETE qi FROM t_order_quote_item qi JOIN t_order_quote q ON qi.quote_id=q.id WHERE q.order_id=?",
        id
      );
      db.update("DELETE FROM t_order_quote WHERE order_id=?", id);
      db.update("DELETE FROM t_service_order WHERE id=?", id);
    }
    for (String key : keys)
      db.update("DELETE FROM t_idempotency WHERE idempotency_key=?", key);
  }

  @Test
  void publishConfirmAndApprovedProjectionSurviveCatalogChanges()
    throws Exception {
    long id = arrived();
    var before = bounds(id);
    String sk = key(),
      body = body(2, "4");
    Reply submitted = send("POST", "/" + id + "/quotes", 3, body, sk);
    expect(submitted, 201, null);
    var q = submitted.data().path("quote");
    long qid = q.path("quoteId").asLong();
    assertThat(q.path("totalAmount").asString()).isEqualTo("398.00");
    assertThat(q.path("totalMinutes").asInt()).isEqualTo(90);
    assertThat(q.path("addedMinutes").asInt()).isZero();
    assertThat(q.path("contentHash").asString()).matches("[a-f0-9]{64}");
    assertThat(q.path("targetEnd").asString()).isEqualTo(
      day + "T06:45:00.000Z"
    );
    assertThat(submitted.response.headers().firstValue("Location")).contains(
      "/api/v1/orders/" + id + "/quotes/" + qid
    );
    assertThat(
      db.queryForObject(
        "SELECT committed_end FROM t_service_order WHERE id=?",
        LocalDateTime.class,
        id
      )
    ).isEqualTo(before.get("committed_end"));
    assertThat(
      get("/" + id, 1)
        .data()
        .path("approvedItems")
        .size()
    ).isZero();
    assertThat(
      send("POST", "/" + id + "/quotes", 3, body, sk).data()
    ).isEqualTo(submitted.data());
    String ck = key();
    Reply confirmed = decide(id, qid, false, 1, "1", ck, null);
    expect(confirmed, 200, null);
    assertThat(confirmed.data().path("state").asString()).isEqualTo(
      "READY_FOR_SERVICE"
    );
    assertThat(confirmed.data().path("effectiveEnd")).isEqualTo(
      confirmed.data().path("committedEnd")
    );
    var original = db.queryForMap(
      "SELECT price,version,name FROM t_service_item WHERE id=1"
    );
    try {
      db.update(
        "UPDATE t_service_item SET price=999.00,version=version+1,name='changed' WHERE id=1"
      );
      var detail = get("/" + id, 1).data();
      assertThat(detail.path("approvedQuoteVersion").asString()).isEqualTo("1");
      assertThat(
        detail.path("approvedItems").get(0).path("unitPrice").asString()
      ).isEqualTo("199.00");
      assertThat(
        get("/" + id + "/quotes", 3)
          .data()
          .path("items")
          .get(0)
          .path("totalAmount")
          .asString()
      ).isEqualTo("398.00");
    } finally {
      db.update(
        "UPDATE t_service_item SET price=?,version=?,name=? WHERE id=1",
        original.get("price"),
        original.get("version"),
        original.get("name")
      );
    }
    db.update(
      "UPDATE t_service_order SET state='SERVICING',row_version=row_version+1 WHERE id=?",
      id
    );
    Reply replay = decide(id, qid, false, 1, "1", ck, null);
    expect(replay, 200, null);
    assertThat(replay.data()).isEqualTo(confirmed.data());
    assertThat(
      replay.response.headers().firstValue("Idempotency-Replayed")
    ).contains("true");
  }

  @Test
  void rejectionReleasesOnlyTemporaryExtensionAndCanRepublish()
    throws Exception {
    long id = arrived();
    var old = bounds(id);
    long q = publish(id, 2);
    String k = key();
    Reply rejected = decide(id, q, true, 1, "1", k, "  too much  ");
    expect(rejected, 200, null);
    assertThat(bounds(id)).isEqualTo(old);
    assertThat(
      get("/" + id, 1)
        .data()
        .path("state")
        .asString()
    ).isEqualTo("ARRIVED");
    assertThat(
      db.queryForObject(
        "SELECT terminal_reason FROM t_order_quote WHERE id=?",
        String.class,
        q
      )
    ).isEqualTo("too much");
    assertThat(decide(id, q, true, 1, "1", k, "too much").data()).isEqualTo(
      rejected.data()
    );
    expect(
      decide(id, q, false, 1, "1", key(), null),
      409,
      "QUOTE_STATE_CONFLICT"
    );
    Reply next = send("POST", "/" + id + "/quotes", 3, body(1, "6"), key());
    expect(next, 201, null);
    assertThat(next.data().path("quote").path("version").asString()).isEqualTo(
      "2"
    );
    assertThat(
      get("/" + id + "/quotes?pageSize=1", 1)
        .data()
        .path("items")
        .get(0)
        .path("version")
        .asString()
    ).isEqualTo("2");
    assertThat(expiry.expire(id, q, 1)).isFalse();
  }

  @Test
  void shorterQuoteDoesNotShrinkCommittedReservation() throws Exception {
    long id = arrived();
    db.update(
      "UPDATE t_service_order SET committed_end=service_start+INTERVAL 3 HOUR,effective_end=service_start+INTERVAL 3 HOUR WHERE id=?",
      id
    );
    var before = bounds(id);
    long q = publish(id, 1);
    expect(decide(id, q, false, 1, "1", key(), null), 200, null);
    assertThat(bounds(id)).isEqualTo(before);
  }

  @Test
  void ownershipRolesNestedIdsAndReplayReauthorization() throws Exception {
    long id = arrived();
    String b = body(1, "4");
    expect(
      send("POST", "/" + id + "/quotes", 1, "{bad", key()),
      403,
      "FORBIDDEN"
    );
    expect(
      send("POST", "/" + id + "/quotes", 4, b, key()),
      404,
      "RESOURCE_NOT_FOUND"
    );
    long q = publish(id, 1);
    expect(get("/" + id + "/quotes", 2), 404, "RESOURCE_NOT_FOUND");
    expect(get("/" + id + "/quotes", 5), 403, "FORBIDDEN");
    expect(decide(id, q, false, 3, "1", key(), null), 403, "FORBIDDEN");
    expect(
      decide(id, q, false, 2, "1", key(), null),
      404,
      "RESOURCE_NOT_FOUND"
    );
    expect(
      decide(id, q + 999999, false, 1, "1", key(), null),
      404,
      "RESOURCE_NOT_FOUND"
    );
    long other = arrived(1);
    expect(
      decide(other, q, false, 1, "1", key(), null),
      404,
      "RESOURCE_NOT_FOUND"
    );
    String k = key();
    expect(decide(id, q, false, 1, "1", k, null), 200, null);
    db.update("UPDATE t_user SET enabled=0 WHERE id=1");
    try {
      expect(decide(id, q, false, 1, "1", k, null), 401, "UNAUTHENTICATED");
    } finally {
      db.update("UPDATE t_user SET enabled=1 WHERE id=1");
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{}",
      "null",
      "[]",
      "{\"purpose\":\"EXTRA\"}",
      "{\"purpose\":\"INITIAL\",\"expectedRowVersion\":4}",
      "{\"items\":[]}",
      "{\"price\":\"1.00\"}",
    }
  )
  void invalidSubmitBodyRejected(String b) throws Exception {
    long id = arrived();
    expect(
      send("POST", "/" + id + "/quotes", 3, b, key()),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "missingApproved",
      "missingPending",
      "duplicate",
      "unknownItem",
      "badQuantity",
      "nullQuantity",
      "badVersion",
      "badLineKey",
      "nullReason",
      "controlReason",
      "nullItems",
      "tooMany",
      "totalOverflow",
    }
  )
  void strictInputValidation(String mode) throws Exception {
    long id = arrived();
    String b = body(1, "4");
    b = switch (mode) {
      case "missingApproved" -> b.replace(
        "\"expectedApprovedQuoteId\":null,",
        ""
      );
      case "missingPending" -> b.replace(
        "\"expectedPendingQuoteId\":null,",
        ""
      );
      case "duplicate" -> b.replace(
        "\"items\":[",
        "\"items\":[" + item(1) + ","
      );
      case "unknownItem" -> b.replace(
        "\"lineKey\":",
        "\"unitPrice\":\"1.00\",\"lineKey\":"
      );
      case "badQuantity" -> b.replace("\"quantity\":1", "\"quantity\":1.5");
      case "nullQuantity" -> b.replace("\"quantity\":1", "\"quantity\":null");
      case "badVersion" -> b.replace(
        "\"catalogVersion\":\"1\"",
        "\"catalogVersion\":\"01\""
      );
      case "badLineKey" -> b.replace("base-1", "bad key");
      case "nullReason" -> b.substring(0, b.length() - 1) + ",\"reason\":null}";
      case "controlReason" -> b.substring(0, b.length() - 1) +
        ",\"reason\":\"bad\\nvalue\"}";
      case "nullItems" -> b.replace("[" + item(1) + "]", "null");
      case "tooMany" -> b.replace(
        "[" + item(1) + "]",
        "[" + String.join(",", Collections.nCopies(51, item(1))) + "]"
      );
      case "totalOverflow" -> body(99, "4").replace(
        "[" + item(99) + "]",
        "[" +
          item(99) +
          "," +
          item(99).replace("base-1", "base-2") +
          "," +
          item(99).replace("base-1", "base-3") +
          "]"
      );
      default -> throw new IllegalStateException();
    };
    expect(
      send("POST", "/" + id + "/quotes", 3, b, key()),
      400,
      "INVALID_ARGUMENT"
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_quote WHERE order_id=?",
        Integer.class,
        id
      )
    ).isZero();
  }

  @Test
  void baselinesVersionCatalogAvailabilityAndWorkWindowErrors()
    throws Exception {
    long id = arrived();
    String b = body(1, "4");
    expect(
      send(
        "POST",
        "/" + id + "/quotes",
        3,
        b.replace(
          "\"expectedPendingQuoteId\":null",
          "\"expectedPendingQuoteId\":\"99\""
        ),
        key()
      ),
      409,
      "QUOTE_VERSION_CONFLICT"
    );
    expect(
      send("POST", "/" + id + "/quotes", 3, body(1, "3"), key()),
      409,
      "ORDER_VERSION_CONFLICT"
    );
    expect(
      send(
        "POST",
        "/" + id + "/quotes",
        3,
        b.replace("\"catalogVersion\":\"1\"", "\"catalogVersion\":\"2\""),
        key()
      ),
      409,
      "CATALOG_VERSION_CONFLICT"
    );
    expect(
      send(
        "POST",
        "/" + id + "/quotes",
        3,
        b.replace("\"serviceItemId\":\"1\"", "\"serviceItemId\":\"5\""),
        key()
      ),
      409,
      "SERVICE_ITEM_UNAVAILABLE"
    );
    expect(
      send("POST", "/" + id + "/quotes", 3, body(10, "4"), key()),
      409,
      "WORK_WINDOW_VIOLATION"
    );
    assertThat(
      get("/" + id + "/quotes", 1)
        .data()
        .path("items")
        .size()
    ).isZero();
  }

  @Test
  void preoccupationConflictLeavesNoHalfQuote() throws Exception {
    long id = arrived();
    long another = create(0, "07:00");
    var old = bounds(id);
    expect(
      send("POST", "/" + id + "/quotes", 3, body(2, "4"), key()),
      409,
      "SLOT_CONFLICT"
    );
    assertThat(bounds(id)).isEqualTo(old);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_quote WHERE order_id=?",
        Integer.class,
        id
      )
    ).isZero();
  }

  @Test
  void expiryBeforeScanErrorAndDoubleScanReleaseExactlyOnce() throws Exception {
    long id = arrived();
    var old = bounds(id);
    long q = publish(id, 2);
    db.update(
      "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
      q
    );
    expect(decide(id, q, false, 1, "1", key(), null), 409, "QUOTE_EXPIRED");
    assertThat(
      get("/" + id, 1)
        .data()
        .path("state")
        .asString()
    ).isEqualTo("QUOTE_PENDING");
    var results = race(
      () -> expiry.expire(id, q, 1),
      () -> expiry.expire(id, q, 1)
    );
    assertThat(results).containsExactlyInAnyOrder(true, false);
    assertThat(bounds(id)).isEqualTo(old);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='EXPIRE_QUOTE'",
        Integer.class,
        id
      )
    ).isEqualTo(1);
    expect(decide(id, q, false, 1, "1", key(), null), 409, "QUOTE_EXPIRED");
  }

  @Test
  void sameKeyReplayAndDifferentPayloadConflict() throws Exception {
    long id = arrived();
    String k = key(),
      b = body(1, "4");
    List<Reply> replies = race(
      () -> unchecked("POST", "/" + id + "/quotes", 3, b, k),
      () -> unchecked("POST", "/" + id + "/quotes", 3, b, k)
    );
    assertThat(replies).allSatisfy(r -> expect(r, 201, null));
    assertThat(replies.get(0).data()).isEqualTo(replies.get(1).data());
    assertThat(
      replies
        .stream()
        .filter(r ->
          r.response.headers().firstValue("Idempotency-Replayed").isPresent()
        )
        .count()
    ).isEqualTo(1);
    expect(
      send("POST", "/" + id + "/quotes", 3, body(2, "4"), k),
      409,
      "IDEMPOTENCY_KEY_REUSED"
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_quote WHERE order_id=?",
        Integer.class,
        id
      )
    ).isEqualTo(1);
  }

  @Test
  void confirmAgainstRejectHasOneCommit() throws Exception {
    long id = arrived(),
      q = publish(id, 2);
    var replies = race(
      () ->
        unchecked(
          "POST",
          "/" + id + "/quotes/" + q + "/confirm",
          1,
          "{\"version\":\"1\"}",
          key()
        ),
      () ->
        unchecked(
          "POST",
          "/" + id + "/quotes/" + q + "/reject",
          1,
          "{\"version\":\"1\",\"reason\":\"no\"}",
          key()
        )
    );
    assertThat(
      replies
        .stream()
        .filter(r -> r.status() == 200)
        .count()
    ).isEqualTo(1);
    assertThat(
      replies
        .stream()
        .filter(r -> r.status() == 409)
        .count()
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT row_version FROM t_service_order WHERE id=?",
        Integer.class,
        id
      )
    ).isEqualTo(6);
  }

  @Test
  void dueConfirmationAgainstExpiryNeverConfirms() throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    db.update(
      "UPDATE t_order_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",
      q
    );
    List<Object> r = race(
      () ->
        unchecked(
          "POST",
          "/" + id + "/quotes/" + q + "/confirm",
          1,
          "{\"version\":\"1\"}",
          key()
        ),
      () -> expiry.expire(id, q, 1)
    );
    expect((Reply) r.get(0), 409, "QUOTE_EXPIRED");
    assertThat(r.get(1)).isEqualTo(true);
  }

  @Test
  void failureAfterAllBusinessWritesRollsEverythingBackAndSameKeyRetries()
    throws Exception {
    long id = arrived();
    var old = bounds(id);
    String k = key();
    rootDb().execute(
      "CREATE TRIGGER d1_fail_success BEFORE UPDATE ON t_idempotency FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected rollback'"
    );
    expect(
      send("POST", "/" + id + "/quotes", 3, body(2, "4"), k),
      500,
      "INTERNAL_ERROR"
    );
    assertThat(bounds(id)).isEqualTo(old);
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
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
        Integer.class,
        k
      )
    ).isZero();
    db.execute("DROP TRIGGER d1_fail_success");
    expect(send("POST", "/" + id + "/quotes", 3, body(2, "4"), k), 201, null);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{}",
      "{\"version\":1}",
      "{\"version\":null}",
      "{\"version\":\"01\"}",
      "{\"version\":\"1\",\"items\":[]}",
    }
  )
  void invalidDecisionBodyRejected(String b) throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    expect(
      send("POST", "/" + id + "/quotes/" + q + "/confirm", 1, b, key()),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @Test
  void missingRejectionReasonVersionPrecedenceAndQueryValidation()
    throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    expect(
      decide(id, q, false, 1, "2", key(), null),
      409,
      "QUOTE_VERSION_CONFLICT"
    );
    expect(
      send(
        "POST",
        "/" + id + "/quotes/" + q + "/reject",
        1,
        "{\"version\":\"1\"}",
        key()
      ),
      400,
      "INVALID_ARGUMENT"
    );
    for (String query : List.of(
      "page=0",
      "pageSize=101",
      "page=1&page=2",
      "status=PENDING"
    ))
      expect(get("/" + id + "/quotes?" + query, 1), 400, "INVALID_ARGUMENT");
    expect(
      send(
        "POST",
        "/" + id + "/quotes/" + q + "/confirm",
        1,
        "{\"version\":\"1\"}",
        "short"
      ),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @Test
  void exactDeadlineMinusOneEqualPlusOneAndVersionPriority() throws Exception {
    for (int offset : new int[] { -1, 0, 1 }) {
      long id = arrived(offset + 1),
        q = publish(id, 1);
      String stamp = "2027-01-01 00:00:00.000";
      db.update("UPDATE t_order_quote SET expires_at=? WHERE id=?", stamp, q);
      TransactionTemplate tx = new TransactionTemplate(manager);
      tx.execute(s -> {
        // 真实应用连接的数据库时间固定到毫秒边界；结束前必须复原会话。
        db.execute("SET timestamp=" + (1798761600.0 + offset / 1000.0));
        installOwner();
        try {
          if (offset < 0) service.decide(
            1,
            "" + id,
            "" + q,
            json.readTree("{\"version\":\"1\"}"),
            key(),
            UUID.randomUUID().toString(),
            false
          );
          else assertThatThrownBy(() ->
            service.decide(
              1,
              "" + id,
              "" + q,
              json.readTree("{\"version\":\"1\"}"),
              key(),
              UUID.randomUUID().toString(),
              false
            )
          )
            .isInstanceOf(com.autocare.common.error.ApiException.class)
            .satisfies(e ->
              assertThat(
                ((com.autocare.common.error.ApiException) e).errorCode().name()
              ).isEqualTo("QUOTE_EXPIRED")
            );
          s.setRollbackOnly();
          return null;
        } finally {
          db.execute("SET timestamp=0");
          SecurityContextHolder.clearContext();
        }
      });
    }
  }

  @Test
  void actualApplicationConnectionMysqlRcUtcInnoDb() {
    var session = db.queryForMap(
      "SELECT VERSION() version,@@session.transaction_isolation isolation_level,@@session.time_zone timezone,@@session.innodb_lock_wait_timeout lock_wait"
    );
    System.out.println("D1 DB_SESSION " + session);
    assertThat(session.get("version").toString()).startsWith("8.4.");
    assertThat(session.get("isolation_level")).isEqualTo("READ-COMMITTED");
    assertThat(session.get("timezone").toString()).isIn("+00:00", "UTC");
    assertThat(session.get("lock_wait").toString()).isEqualTo("5");
    assertThat(
      db.queryForObject(
        "SELECT ENGINE FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='t_order_quote'",
        String.class
      )
    ).isEqualTo("InnoDB");
  }

  @Test
  void confirmationRollbackRestoresPendingAndRetrySucceeds() throws Exception {
    long id = arrived(),
      q = publish(id, 2);
    var old = bounds(id);
    String k = key();
    rootDb().execute(
      "CREATE TRIGGER d1_fail_success BEFORE UPDATE ON t_idempotency FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected decision rollback'"
    );
    expect(decide(id, q, false, 1, "1", k, null), 500, "INTERNAL_ERROR");
    assertThat(bounds(id)).isEqualTo(old);
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        q
      )
    ).isEqualTo("PENDING");
    assertThat(
      db.queryForObject(
        "SELECT state FROM t_service_order WHERE id=?",
        String.class,
        id
      )
    ).isEqualTo("QUOTE_PENDING");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='CONFIRM_QUOTE'",
        Integer.class,
        id
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",
        Integer.class,
        k
      )
    ).isZero();
    db.execute("DROP TRIGGER d1_fail_success");
    expect(decide(id, q, false, 1, "1", k, null), 200, null);
  }

  @Test
  void simultaneousConfirmSameKeyCommitsOnce() throws Exception {
    long id = arrived(),
      q = publish(id, 1);
    String k = key(),
      path = "/" + id + "/quotes/" + q + "/confirm";
    var replies = race(
      () -> unchecked("POST", path, 1, "{\"version\":\"1\"}", k),
      () -> unchecked("POST", path, 1, "{\"version\":\"1\"}", k)
    );
    assertThat(replies).allSatisfy(r -> expect(r, 200, null));
    assertThat(replies.get(0).data()).isEqualTo(replies.get(1).data());
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='CONFIRM_QUOTE'",
        Integer.class,
        id
      )
    ).isEqualTo(1);
  }

  @Test
  void simultaneousDifferentPublishCommandsCannotCreateTwoPendingQuotes()
    throws Exception {
    long id = arrived();
    String a = key(),
      b = key();
    var replies = race(
      () -> unchecked("POST", "/" + id + "/quotes", 3, body(1, "4"), a),
      () -> unchecked("POST", "/" + id + "/quotes", 3, body(2, "4"), b)
    );
    assertThat(
      replies
        .stream()
        .filter(r -> r.status() == 201)
        .count()
    ).isEqualTo(1);
    assertThat(
      replies
        .stream()
        .filter(r -> r.status() == 409)
        .count()
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_order_quote WHERE order_id=? AND status='PENDING'",
        Integer.class,
        id
      )
    ).isEqualTo(1);
  }

  @Test
  void completeMultipleItemSnapshotsAndRuleWindowAreUsed() throws Exception {
    long id = arrived();
    db.update(
      "UPDATE t_service_order SET rules_snapshot_json=JSON_SET(rules_snapshot_json,'$.quoteConfirmWindowMinutes',3) WHERE id=?",
      id
    );
    String second = item(2)
      .replace("base-1", "base-2")
      .replace("\"serviceItemId\":\"1\"", "\"serviceItemId\":\"2\"");
    String body = body(1, "4").replace(
      "[" + item(1) + "]",
      "[" + item(1) + "," + second + "]"
    );
    Reply r = send("POST", "/" + id + "/quotes", 3, body, key());
    expect(r, 201, null);
    var q = r.data().path("quote");
    assertThat(q.path("items").size()).isEqualTo(2);
    assertThat(q.path("totalAmount").asString()).isEqualTo("377.00");
    assertThat(q.path("totalMinutes").asInt()).isEqualTo(85);
    assertThat(
      Duration.between(
        Instant.parse(q.path("submittedAt").asString()),
        Instant.parse(q.path("expiresAt").asString())
      )
    ).isEqualTo(Duration.ofMinutes(3));
    long qid = q.path("quoteId").asLong();
    assertThat(expiry.expire(id, qid, 1)).isFalse();
    assertThat(expiry.expire(id, qid, 2)).isFalse();
  }

  long arrived() throws Exception {
    return arrived(0);
  }

  long arrived(int offset) throws Exception {
    long id = create(offset, "05:00");
    for (var action : List.of("accept", "depart", "arrive")) {
      String v = action.equals("accept")
        ? "1"
        : action.equals("depart")
          ? "2"
          : "3";
      expect(
        send(
          "POST",
          "/" + id + "/" + action,
          3,
          "{\"expectedRowVersion\":\"" + v + "\"}",
          key()
        ),
        200,
        null
      );
    }
    return id;
  }

  long create(int offset, String time) throws Exception {
    Reply r = send(
      "POST",
      "",
      1,
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
        day.plusDays(offset) +
        "T" +
        time +
        ":00.000Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"d1 synthetic\"}",
      key()
    );
    expect(r, 201, null);
    long id = r.data().path("orderId").asLong();
    ids.add(id);
    return id;
  }

  long publish(long id, int quantity) throws Exception {
    Reply r = send("POST", "/" + id + "/quotes", 3, body(quantity, "4"), key());
    expect(r, 201, null);
    return r.data().path("quote").path("quoteId").asLong();
  }

  String item(int quantity) {
    return (
      "{\"lineKey\":\"base-1\",\"serviceItemId\":\"1\",\"quantity\":" +
      quantity +
      ",\"catalogVersion\":\"1\"}"
    );
  }

  String body(int quantity, String version) {
    return (
      "{\"purpose\":\"INITIAL\",\"expectedRowVersion\":\"" +
      version +
      "\",\"expectedApprovedQuoteId\":null,\"expectedPendingQuoteId\":null,\"items\":[" +
      item(quantity) +
      "]}"
    );
  }

  Reply decide(
    long id,
    long q,
    boolean reject,
    long actor,
    String version,
    String k,
    String reason
  ) throws Exception {
    return send(
      "POST",
      "/" + id + "/quotes/" + q + (reject ? "/reject" : "/confirm"),
      actor,
      "{\"version\":\"" +
        version +
        "\"" +
        (reason == null
          ? ""
          : ",\"reason\":" + json.writeValueAsString(reason)) +
        "}",
      k
    );
  }

  Reply get(String path, long actor) throws Exception {
    return send("GET", path, actor, null, null);
  }

  Reply send(String method, String path, long actor, String body, String k)
    throws Exception {
    Role role = actor <= 2 ? Role.OWNER : actor <= 4 ? Role.TECH : Role.ADMIN;
    var b = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api/v1/orders" + path)
    )
      .timeout(Duration.ofSeconds(15))
      .header(
        "Authorization",
        "Bearer " + issuer.issue(actor, role, Instant.now())
      )
      .header("Accept", "application/json")
      .header("X-Request-Id", UUID.randomUUID().toString());
    if (k != null) b.header("Idempotency-Key", k);
    if (body != null) b.header("Content-Type", "application/json");
    var response = client.send(
      b
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    System.out.println(
      "D1 HTTP " +
        method +
        " " +
        path +
        " status=" +
        response.statusCode() +
        " body=" +
        response.body()
    );
    synchronized (QuoteApiTest.class) {
      java.nio.file.Files.writeString(
        java.nio.file.Path.of("target", "quote-http-evidence.jsonl"),
        json.writeValueAsString(Map.of("method", method, "path", path, "status", response.statusCode(),
          "response", json.readTree(response.body()))) + "\n",
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      );
    }
    return new Reply(response, json.readTree(response.body()));
  }

  Reply unchecked(
    String method,
    String path,
    long actor,
    String body,
    String k
  ) {
    try {
      return send(method, path, actor, body, k);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  void expect(Reply r, int status, String code) {
    assertThat(r.status()).describedAs(r.body.toString()).isEqualTo(status);
    if (code != null) assertThat(
      r.body.path("error").path("code").asString()
    ).isEqualTo(code);
  }

  String key() {
    String k = UUID.randomUUID().toString();
    keys.add(k);
    return k;
  }

  JdbcTemplate rootDb() {
    return new JdbcTemplate(
      new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:mysql://127.0.0.1:13306/" +
          System.getenv("DB_NAME") +
          "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
        "root",
        System.getenv("DB_ROOT_PASSWORD")
      )
    );
  }

  Map<String, Object> bounds(long id) {
    return db.queryForMap(
      "SELECT committed_end,effective_end FROM t_service_order WHERE id=?",
      id
    );
  }

  void installOwner() {
    SecurityContextHolder.getContext().setAuthentication(
      new JwtAuthenticationToken(
        Jwt.withTokenValue("test").header("alg", "HS256").subject("1").build()
      )
    );
  }

  <T> List<T> race(Supplier<? extends T> a, Supplier<? extends T> b)
    throws Exception {
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<T>> fs = new ArrayList<>();
      for (var action : List.of(a, b))
        fs.add(
          pool.submit(() -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(
              "barrier"
            );
            return action.get();
          })
        );
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      List<T> result = new ArrayList<>();
      for (var f : fs) result.add(f.get(20, TimeUnit.SECONDS));
      return result;
    }
  }

  record Reply(HttpResponse<String> response, JsonNode body) {
    int status() {
      return response.statusCode();
    }

    JsonNode data() {
      return body.path("data");
    }
  }
}
