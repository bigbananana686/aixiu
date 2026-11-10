package com.autocare.server.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.analysis.*;
import com.autocare.ai.knowledge.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

/** 本机受控HTTP确实经过SpringAI适配器；不作为真实厂商质量证据。 */
class D5AdapterTest {

  HttpServer server;
  AtomicReference<String> output = new AtomicReference<>(),
    input = new AtomicReference<>(),
    path = new AtomicReference<>();
  int status = 200;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      path.set(exchange.getRequestURI().getPath());
      input.set(
        new String(
          exchange.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8
        )
      );
      byte[] bytes = output.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  URI url() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Test
  void embeddingSdkSendsBatchAndReadsConfiguredDimensions() {
    output.set(
      "{\"object\":\"list\",\"model\":\"controlled-embedding\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1.0,0.0]},{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.0,1.0]}],\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}"
    );
    var port = SpringEmbeddingPort.openAi(
      URI.create(url() + "/v1"),
      "controlled-only-key",
      "controlled-embedding",
      2,
      Duration.ofSeconds(2)
    );
    var result = port.embed(List.of("机油", "滤芯"));
    assertThat(result).hasSize(2);
    assertThat(result.getFirst()).containsExactly(1, 0);
    assertThat(path.get()).isEqualTo("/v1/embeddings");
    assertThat(input.get()).contains(
      "controlled-embedding",
      "dimensions",
      "机油"
    );
  }

  @Test
  void embeddingDependencyFailureIsNotReplacedByFakeVectors() {
    status = 503;
    output.set(
      "{\"error\":{\"message\":\"controlled unavailable\",\"type\":\"server_error\",\"code\":\"unavailable\"}}"
    );
    var port = SpringEmbeddingPort.openAi(
      url(),
      "controlled-only-key",
      "controlled-embedding",
      2,
      Duration.ofSeconds(1)
    );
    assertThatThrownBy(() -> port.embed(List.of("text"))).isInstanceOf(
      RuntimeException.class
    );
  }

  String chat(String content) {
    String escaped = tools.jackson.databind.json.JsonMapper.builder()
      .build()
      .writeValueAsString(content);
    return (
      "{\"id\":\"controlled-chat\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"controlled-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" +
      escaped +
      "},\"finish_reason\":\"stop\"}]}"
    );
  }

  @Test
  void chatSdkStrictOutputKeepsUnknownUsageNull() {
    output.set(
      chat(
        "{\"findings\":[],\"missingEvidence\":[\"缺少资料\"],\"citations\":[],\"limitations\":[\"人工核对\"]}"
      )
    );
    var answer = SpringQuoteAnalyzer.deepSeek(
      url(),
      "controlled-only-key",
      "controlled-chat",
      Duration.ofSeconds(2)
    ).analyze(
      new QuoteAnalyzer.Context("1", "1", "a".repeat(64), "GENERIC", "{}"),
      List.of()
    );
    assertThat(answer.result().missingEvidence()).containsExactly("缺少资料");
    assertThat(answer.promptTokens()).isNull();
    assertThat(answer.totalTokens()).isNull();
    assertThat(input.get())
      .contains("authorizedEvidence", "quoteJson", "modelCode")
      .doesNotContain("tool_calls");
  }

  @Test
  void chatUnknownFieldsAreValidationFailure() {
    output.set(
      chat(
        "{\"findings\":[],\"missingEvidence\":[],\"citations\":[],\"limitations\":[],\"approve\":true}"
      )
    );
    var analyzer = SpringQuoteAnalyzer.deepSeek(
      url(),
      "controlled-only-key",
      "controlled-chat",
      Duration.ofSeconds(2)
    );
    assertThatThrownBy(() ->
      analyzer.analyze(
        new QuoteAnalyzer.Context("1", "1", "a".repeat(64), "GENERIC", "{}"),
        List.of()
      )
    ).isInstanceOf(AnalysisValidator.InvalidAnalysisException.class);
  }
}
