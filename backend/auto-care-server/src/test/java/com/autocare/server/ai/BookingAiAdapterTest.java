package com.autocare.server.ai;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.booking.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.deepseek.*;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.core.retry.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class BookingAiAdapterTest {

  HttpServer server;
  BookingDraftParser parser;
  volatile String output;
  volatile int status = 200,
    requests;
  volatile String requestBody;
  volatile boolean includeUsage = true;
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void setup() throws Exception {
    output = json.writeValueAsString(DraftTestSupport.draft());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      requests++;
      requestBody = new String(
        exchange.getRequestBody().readAllBytes(),
        StandardCharsets.UTF_8
      );
      String payload =
        status == 200
          ? json.writeValueAsString(
              Map.of(
                "id",
                "synthetic-call",
                "object",
                "chat.completion",
                "created",
                1700000000,
                "model",
                "fixture-model",
                "choices",
                List.of(
                  Map.of(
                    "index",
                    0,
                    "message",
                    Map.of("role", "assistant", "content", output),
                    "finish_reason",
                    "stop"
                  )
                ),
                "usage",
                Map.of(
                  "prompt_tokens",
                  12,
                  "completion_tokens",
                  8,
                  "total_tokens",
                  20
                )
              )
            )
          : "{\"error\":{\"message\":\"synthetic provider down\",\"type\":\"unavailable\"}}";
      if (!includeUsage) {
        var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(
          payload
        );
        tree.remove("usage");
        payload = json.writeValueAsString(tree);
      }
      byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    parser = SpringBookingDraftParser.deepSeek(
      URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
      "fixture-key-not-a-credential",
      "fixture-model",
      Duration.ofSeconds(2)
    );
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
  }

  @Test
  void actualSpringAiAdapter_sendsServerTimeAndReturnsStructuredDtoAndUsage() {
    var r = parser.parse(
      "抖动",
      Instant.parse("2026-11-05T01:00:00Z"),
      "Asia/Shanghai"
    );
    assertThat(r.draft().symptoms()).containsExactly("抖动");
    assertThat(r.modelId()).isEqualTo("fixture-model");
    assertThat(r.totalTokens()).isEqualTo(20);
    assertThat(requestBody).contains(
      "2026-11-05T01:00:00Z",
      "Asia/Shanghai",
      "抖动"
    );
    assertThat(requests).isEqualTo(1);
  }

  @Test
  void malformedJson_isValidationFailure() {
    output = "not JSON";
    assertThatThrownBy(() ->
      parser.parse("抖动", Instant.now(), "Asia/Shanghai")
    ).isInstanceOf(DraftValidationException.class);
    assertThat(requests).isEqualTo(1);
  }

  @Test
  void unexpectedModelCommands_areRejected() {
    output =
      output.substring(0, output.length() - 1) +
      ",\"ownerId\":\"2\",\"createOrder\":true}";
    assertThatThrownBy(() ->
      parser.parse("抖动", Instant.now(), "Asia/Shanghai")
    ).isInstanceOf(DraftValidationException.class);
  }

  @Test
  void missingRequiredModelField_isRejected() {
    output = json.writeValueAsString(
      Map.of("vehicleHints", Map.of("mileage", 1000))
    );
    assertThatThrownBy(() ->
      parser.parse("抖动", Instant.now(), "Asia/Shanghai")
    ).isInstanceOf(DraftValidationException.class);
  }

  @Test
  void providerUnavailable_hasNoHiddenRetries() {
    status = 503;
    assertThatThrownBy(() ->
      parser.parse("抖动", Instant.now(), "Asia/Shanghai")
    ).isInstanceOf(RuntimeException.class);
    assertThat(requests).isEqualTo(1);
  }

  @Test
  void absentUsage_remainsNullRatherThanInventedZero() {
    includeUsage = false;
    var r = parser.parse("抖动", Instant.now(), "Asia/Shanghai");
    assertThat(r.promptTokens()).isNull();
    assertThat(r.completionTokens()).isNull();
    assertThat(r.totalTokens()).isNull();
  }
}
