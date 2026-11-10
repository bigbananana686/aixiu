package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.autocare.common.identity.Role;
import com.autocare.server.order.quote.*;
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
abstract class D2OrderSupport {

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
      db.update("DELETE FROM t_order_exception WHERE order_id=?", id);
      db.update(
        "UPDATE t_order_quote SET base_approved_quote_id=NULL WHERE order_id=?",
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
      URI.create(
        "http://127.0.0.1:" +
          port +
          (path.startsWith("/api/") ? path : "/api/v1/orders" + path)
      )
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
      "D2 HTTP " +
        method +
        " " +
        path +
        " status=" +
        response.statusCode() +
        " body=" +
        response.body()
    );
    synchronized (D2OrderSupport.class) {
      java.nio.file.Files.writeString(
        java.nio.file.Path.of("target", "d2-http-evidence.jsonl"),
        json.writeValueAsString(
          Map.of(
            "method",
            method,
            "path",
            path,
            "status",
            response.statusCode(),
            "response",
            json.readTree(response.body())
          )
        ) + "\n",
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
