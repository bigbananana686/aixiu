package com.autocare.server.ai;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.booking.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "autocare.tasks.enabled=false",
    "autocare.ai.booking.per-minute=100",
  }
)
@Import(BookingDraftApiTest.Config.class)
class BookingDraftApiTest extends DraftTestSupport {

  @TestConfiguration
  static class Config {

    @Bean
    @Primary
    StubParser stubParser() {
      return new StubParser();
    }
  }

  @BeforeEach
  void setup() {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class))
      .matches("auto_care_g104_[a-z0-9_]+")
      .isEqualTo(System.getenv("DB_NAME"));
    stub.answer = DraftTestSupport::draft;
  }

  @AfterEach
  void cleanup() {
    db.update("DELETE FROM t_ai_call_log WHERE scene='BOOKING_DRAFT'");
    db.update("UPDATE t_user SET enabled=1 WHERE id=1");
  }

  @Test
  void successfulDraft_returnsNullUnknownFacts_andDoesNotCreateOrder()
    throws Exception {
    int before = db.queryForObject(
      "SELECT COUNT(*) FROM t_service_order",
      Integer.class
    );
    var r = send(1, "{\"text\":\"抖动\"}", null);
    expect(r, 200, null);
    assertThat(
      r.data().path("vehicleHints").path("modelCode").isNull()
    ).isTrue();
    assertThat(r.data().path("preferredTimeRange").isNull()).isTrue();
    assertThat(
      r.http().headers().firstValue("Cache-Control").orElse("")
    ).contains("no-store");
    assertThat(
      db.queryForObject("SELECT COUNT(*) FROM t_service_order", Integer.class)
    ).isEqualTo(before);
    var record = db.queryForMap(
      "SELECT * FROM t_ai_call_log WHERE id=?",
      r.data().path("aiParseId").asLong()
    );
    assertThat(record.get("outcome")).isEqualTo("SUCCEEDED");
    assertThat(record.get("prompt_tokens")).isNull();
    assertThat(record.get("estimated_cost_cny")).isNull();
    assertThat(record.get("input_hash").toString()).matches("[a-f0-9]{64}");
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{}",
      "{\"text\":null}",
      "{\"text\":1}",
      "{\"text\":\"  \"}",
      "{\"text\":\"抖动\",\"ownerId\":\"2\"}",
      "{\"text\":\"抖动\",\"timeZone\":null}",
      "{\"text\":\"抖动\",\"timeZone\":\"UTC\"}",
    }
  )
  void invalidInput_rejectedBeforeProvider(String body) throws Exception {
    stub.answer = () -> {
      throw new AssertionError("Provider must not run");
    };
    expect(send(1, body, null), 400, "INVALID_ARGUMENT");
  }

  @Test
  void text4001Rejected() throws Exception {
    expect(
      send(1, json.writeValueAsString(Map.of("text", "a".repeat(4001))), null),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @Test
  void idempotencyKeyNotSupported() throws Exception {
    var r = send(1, "{\"text\":\"抖动\"}", UUID.randomUUID().toString());
    expect(r, 400, "INVALID_ARGUMENT");
    assertThat(
      r.body().path("error").path("fieldErrors").get(0).path("code").asString()
    ).isEqualTo("NOT_SUPPORTED");
  }

  @Test
  void currentRoleAndAuthenticationGate() throws Exception {
    expect(send(0, "{\"text\":\"抖动\"}", null), 401, "UNAUTHENTICATED");
    expect(send(3, "{\"text\":\"抖动\"}", null), 403, "FORBIDDEN");
    expect(send(5, "{\"text\":\"抖动\"}", null), 403, "FORBIDDEN");
  }

  @Test
  void providerFailure_hasManualMessageAndNoOrderSideEffect() throws Exception {
    stub.answer = () -> {
      throw new IllegalStateException("secret-error-must-not-leak");
    };
    var r = send(1, "{\"text\":\"抖动\"}", null);
    expect(r, 503, "AI_UNAVAILABLE");
    assertThat(r.http().body())
      .contains("手工填写")
      .doesNotContain("secret-error");
    assertThat(r.http().headers().firstValue("Retry-After")).isPresent();
    assertThat(
      db.queryForObject(
        "SELECT outcome FROM t_ai_call_log ORDER BY id DESC LIMIT 1",
        String.class
      )
    ).isEqualTo("FAILED");
  }

  @Test
  void unsupportedFact_isValidationFailure_notSilentSuccess() throws Exception {
    stub.answer = () ->
      new BookingDraft(
        new BookingDraft.VehicleHints("INVENTED", 100, null),
        List.of(),
        null,
        null,
        List.of(),
        List.of(),
        List.of()
      );
    expect(send(1, "{\"text\":\"抖动\"}", null), 503, "AI_UNAVAILABLE");
    assertThat(
      db.queryForObject(
        "SELECT outcome FROM t_ai_call_log ORDER BY id DESC LIMIT 1",
        String.class
      )
    ).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void forgedEvidenceSpan_isRejected() throws Exception {
    stub.answer = () ->
      new BookingDraft(
        new BookingDraft.VehicleHints(null, null, null),
        List.of("抖动"),
        null,
        null,
        List.of(),
        List.of(new BookingDraft.EvidenceSpan("symptoms.0", 1, 2, "抖动")),
        List.of()
      );
    expect(send(1, "{\"text\":\"抖动\"}", null), 503, "AI_UNAVAILABLE");
  }

  @Test
  void unicodeOffsets_andMileageUnitsAreGrounded() throws Exception {
    stub.answer = () ->
      new BookingDraft(
        new BookingDraft.VehicleHints(null, 15000, null),
        List.of("抖动"),
        null,
        null,
        List.of(),
        List.of(
          new BookingDraft.EvidenceSpan("mileage", 1, 4, "1万5"),
          new BookingDraft.EvidenceSpan("symptoms.0", 4, 6, "抖动")
        ),
        List.of()
      );
    expect(send(1, "{\"text\":\"🚘1万5抖动\"}", null), 200, null);
  }

  @Test
  void disabledDuringProvider_recheckedBeforeReturningFacts() throws Exception {
    stub.answer = () -> {
      db.update("UPDATE t_user SET enabled=0 WHERE id=1");
      return draft();
    };
    expect(send(1, "{\"text\":\"抖动\"}", null), 401, "UNAUTHENTICATED");
  }

  @Test
  void relativeDateRule_usesServerZone() {
    Instant now = Instant.parse("2026-11-05T16:30:00Z");
    var range = DraftValidator.relative("明天上午", now);
    assertThat(range[0]).isEqualTo(Instant.parse("2026-11-07T00:00:00Z"));
    assertThat(range[1]).isEqualTo(Instant.parse("2026-11-07T04:00:00Z"));
  }

  @Test
  void modelCannotInventAbsoluteDate_orMisreadNextWeek() {
    Instant now = Instant.parse("2026-11-05T01:00:00Z");
    var d = new BookingDraft(
      new BookingDraft.VehicleHints(null, null, null),
      List.of(),
      "2026-11-06上午",
      new BookingDraft.TimeRange(
        "2026-11-07T00:00:00.000Z",
        "2026-11-07T04:00:00.000Z"
      ),
      List.of(),
      List.of(
        new BookingDraft.EvidenceSpan(
          "preferredTimeText",
          0,
          12,
          "2026-11-06上午"
        )
      ),
      List.of()
    );
    assertThatThrownBy(() ->
      DraftValidator.validate(d, "2026-11-06上午", now)
    ).isInstanceOf(DraftValidationException.class);
    assertThat(DraftValidator.relative("下周六上午", now)[0]).isEqualTo(
      Instant.parse("2026-11-14T00:00:00Z")
    );
  }
}
