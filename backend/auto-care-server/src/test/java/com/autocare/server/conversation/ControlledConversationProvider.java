package com.autocare.server.conversation;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 合成DeepSeek协议夹具：真实ChatClient/MemoryAdvisor/ToolCalling均参与。 */
public final class ControlledConversationProvider implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService executor =
    Executors.newVirtualThreadPerTaskExecutor();
  private final ObjectMapper json = JsonMapper.builder().build();
  public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  public volatile boolean fail;
  public volatile CountDownLatch entered = new CountDownLatch(1),
    release = new CountDownLatch(1);

  public ControlledConversationProvider() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", exchange -> {
      var tree = json.readTree(exchange.getRequestBody().readAllBytes());
      requests.add(tree);
      String text = "";
      boolean tool = false;
      for (var m : tree.path("messages")) {
        if ("user".equals(m.path("role").asString())) text = m
          .path("content")
          .asString();
        if ("tool".equals(m.path("role").asString())) tool = true;
      }
      // ChatClient.entity会附加结构说明，夹具按本次用户消息的首行路由。
      text = text.lines().findFirst().orElse("").strip();
      int status = 200;
      String out;
      if (fail) {
        status = 503;
        out = "{\"error\":{\"message\":\"controlled unavailable\"}}";
      } else {
        if (text.equals("等待旧请求")) {
          entered.countDown();
          try {
            release.await(8, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        if (
          text.equals("工具循环") ||
          (!tool &&
            (text.equals("查我的车") ||
              text.equals("报价草稿") ||
              text.startsWith("查订单 ")))
        ) {
          String name = text.equals("报价草稿")
            ? "quoteDraft"
            : text.startsWith("查订单 ")
              ? "orderSummary"
              : "myVehicles";
          String args = text.equals("报价草稿")
            ? "{\"serviceItemIds\":[\"1\"]}"
            : text.startsWith("查订单 ")
              ? json.writeValueAsString(Map.of("orderId", text.substring(4)))
              : "{}";
          var m = new LinkedHashMap<String, Object>();
          m.put("role", "assistant");
          m.put("content", null);
          m.put(
            "tool_calls",
            List.of(
              Map.of(
                "id",
                "call-" + requests.size(),
                "type",
                "function",
                "function",
                Map.of("name", name, "arguments", args)
              )
            )
          );
          out = response(m, "tool_calls");
        } else {
          List<Map<String, String>> updates = new ArrayList<>();
          if (text.contains("COROLLA_2020")) updates.add(
            update("modelCode", "COROLLA_2020", "COROLLA_2020")
          );
          if (text.contains("3万公里")) updates.add(
            update("mileage", "30000", "3万公里")
          );
          if (text.contains("怠速抖动")) updates.add(
            update("symptom", "怠速抖动", "怠速抖动")
          );
          if (text.equals("报价草稿")) updates.add(
            update("serviceItemId", "1", "\"serviceItemId\":\"1\"")
          );
          if (text.equals("伪造里程")) updates.add(
            update("mileage", "999999", "根本没有这段证据")
          );
          out = response(
            Map.of(
              "role",
              "assistant",
              "content",
              json.writeValueAsString(
                Map.of("reply", "收到，继续补充即可。", "updates", updates)
              )
            ),
            "stop"
          );
        }
      }
      byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var body = exchange.getResponseBody()) {
        body.write(bytes);
      }
    });
    server.start();
  }

  private Map<String, String> update(
    String field,
    String value,
    String evidence
  ) {
    return Map.of("field", field, "value", value, "evidence", evidence);
  }

  private String response(Map<String, Object> message, String finish) {
    return json.writeValueAsString(
      Map.of(
        "id",
        "controlled-chat",
        "object",
        "chat.completion",
        "created",
        1700000000,
        "model",
        "controlled-chat",
        "choices",
        List.of(Map.of("index", 0, "message", message, "finish_reason", finish))
      )
    );
  }

  public URI url() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public void reset() {
    fail = false;
    requests.clear();
    entered = new CountDownLatch(1);
    release = new CountDownLatch(1);
  }

  public void close() {
    release.countDown();
    server.stop(0);
    executor.shutdownNow();
  }
}
