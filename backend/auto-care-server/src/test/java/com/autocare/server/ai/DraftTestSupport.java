package com.autocare.server.ai;

import com.autocare.ai.booking.*;
import com.autocare.common.identity.Role;
import com.autocare.server.security.JwtTokenIssuer;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

abstract class DraftTestSupport {

  @Autowired
  JdbcTemplate db;

  @Autowired
  ObjectMapper json;

  @Autowired
  JwtTokenIssuer issuer;

  @LocalServerPort
  int port;

  @Autowired
  StubParser stub;

  static class StubParser implements BookingDraftParser {

    volatile java.util.function.Supplier<BookingDraft> answer =
      DraftTestSupport::draft;

    public Parsed parse(String text, Instant now, String zone) {
      return new Parsed(answer.get(), "test-provider", null, null, null);
    }
  }

  static BookingDraft draft() {
    return new BookingDraft(
      new BookingDraft.VehicleHints(null, null, null),
      List.of("抖动"),
      null,
      null,
      List.of("address", "technicianId"),
      List.of(new BookingDraft.EvidenceSpan("symptoms.0", 0, 2, "抖动")),
      List.of("仅供回填，仍须车主确认")
    );
  }

  record Reply(HttpResponse<String> http, JsonNode body) {
    JsonNode data() {
      return body.path("data");
    }
  }

  Reply send(long actor, String body, String key) throws Exception {
    var b = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api/v1/ai/booking-drafts")
    )
      .timeout(Duration.ofSeconds(25))
      .header("Content-Type", "application/json");
    if (actor > 0) b.header(
      "Authorization",
      "Bearer " +
        issuer.issue(
          actor,
          actor == 3 ? Role.TECH : actor == 5 ? Role.ADMIN : Role.OWNER,
          Instant.now()
        )
    );
    if (key != null) b.header("Idempotency-Key", key);
    var r = HttpClient.newHttpClient().send(
      b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
      HttpResponse.BodyHandlers.ofString()
    );
    System.out.println(
      "D4 HTTP status=" + r.statusCode() + " body=" + r.body()
    );
    synchronized (DraftTestSupport.class) {
      java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/d4-http-evidence.jsonl"),
        json.writeValueAsString(
          Map.of(
            "actor",
            actor,
            "status",
            r.statusCode(),
            "response",
            json.readTree(r.body())
          )
        ) + "\n",
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      );
    }
    return new Reply(r, json.readTree(r.body()));
  }

  void expect(Reply r, int status, String code) {
    org.assertj.core.api.Assertions.assertThat(r.http.statusCode()).isEqualTo(
      status
    );
    if (code != null) org.assertj.core.api.Assertions.assertThat(
      r.body.path("error").path("code").asString()
    ).isEqualTo(code);
  }
}
