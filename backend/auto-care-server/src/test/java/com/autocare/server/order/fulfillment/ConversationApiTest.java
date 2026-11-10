package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.autocare.server.conversation.*;
import com.autocare.server.knowledge.D5Http;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "autocare.ai.booking.per-minute=100",
    "autocare.ai.booking.timeout=5s",
  }
)
@Import(ConversationTestConfiguration.class)
class ConversationApiTest extends D2OrderSupport {

  @Autowired
  ControlledConversationProvider provider;

  D5Http http;
  List<String> conversations = new ArrayList<>();
  static final String ROOT = "/ai/conversations";

  @BeforeEach
  void setupConversation() {
    http = new D5Http(port, issuer, json);
    provider.reset();
  }

  @AfterEach
  void cleanupConversation() {
    provider.release.countDown();
    for (String id : conversations) {
      db.update(
        "DELETE FROM spring_ai_chat_memory WHERE conversation_id=?",
        id
      );
      db.update(
        "DELETE t FROM t_ai_conversation_turn t JOIN t_ai_conversation c ON c.id=t.conversation_id WHERE c.public_id=?",
        id
      );
      db.update("DELETE FROM t_ai_conversation WHERE public_id=?", id);
    }
  }

  void check(D5Http.Reply r, int status, String code) {
    assertThat(r.http().statusCode()).isEqualTo(status);
    if (code != null) assertThat(
      r.body().path("error").path("code").asString()
    ).isEqualTo(code);
  }

  String createConversation(long actor) throws Exception {
    var r = http.json("POST", ROOT, actor, "{}", key());
    check(r, 201, null);
    String id = r.data().path("conversationId").asString();
    conversations.add(id);
    assertThat(r.http().headers().firstValue("Location")).contains(
      "/api/v1" + ROOT + "/" + id
    );
    return id;
  }

  D5Http.Reply send(
    String id,
    long actor,
    String revision,
    String message,
    String key
  ) throws Exception {
    return http.json(
      "POST",
      ROOT + "/" + id + "/messages",
      actor,
      json.writeValueAsString(
        Map.of("expectedRevision", revision, "message", message)
      ),
      key
    );
  }

  D5Http.Reply getConversation(String id, long actor) throws Exception {
    return http.json("GET", ROOT + "/" + id, actor, null, null);
  }

  int memory(String id) {
    return db.queryForObject(
      "SELECT COUNT(*) FROM spring_ai_chat_memory WHERE conversation_id=?",
      Integer.class,
      id
    );
  }

  @Test
  void incrementalFactsReachModelAndAccumulateAcrossTurns() throws Exception {
    String id = createConversation(1);
    var first = send(id, 1, "0", "我是COROLLA_2020，怠速抖动", key());
    check(first, 200, null);
    var second = send(id, 1, "1", "跑了3万公里", key());
    check(second, 200, null);
    var facts = second.data().path("facts");
    assertThat(facts.path("modelCode").asString()).isEqualTo("COROLLA_2020");
    assertThat(facts.path("mileage").asInt()).isEqualTo(30000);
    assertThat(facts.path("symptoms").get(0).asString()).isEqualTo("怠速抖动");
    assertThat(memory(id)).isEqualTo(4);
    assertThat(provider.requests.getLast().toString()).contains(
      "我是COROLLA_2020，怠速抖动",
      "跑了3万公里"
    );
    assertThat(getConversation(id, 1).data().path("turns")).hasSize(2);
  }

  @Test
  void windowEvictionDoesNotLoseValidatedFacts() throws Exception {
    String id = createConversation(1);
    check(send(id, 1, "0", "车型COROLLA_2020", key()), 200, null);
    for (int i = 1; i <= 8; i++) check(
      send(id, 1, "" + i, "随便聊聊第" + i + "轮", key()),
      200,
      null
    );
    assertThat(memory(id)).isLessThanOrEqualTo(12);
    var last = provider.requests.getLast();
    assertThat(last.toString())
      .doesNotContain("车型COROLLA_2020")
      .contains("COROLLA_2020");
    assertThat(
      getConversation(id, 1).data().path("facts").path("modelCode").asString()
    ).isEqualTo("COROLLA_2020");
  }

  @Test
  void concurrentConversationIdsAreScopedAndDeletionClearsMemory()
    throws Exception {
    String a = createConversation(1),
      b = createConversation(1);
    check(send(a, 1, "0", "车型COROLLA_2020", key()), 200, null);
    check(send(b, 1, "0", "另一台车", key()), 200, null);
    assertThat(provider.requests.getLast().toString()).doesNotContain(
      "COROLLA_2020"
    );
    check(getConversation(a, 2), 404, "RESOURCE_NOT_FOUND");
    check(send(a, 2, "1", "非法", key()), 404, "RESOURCE_NOT_FOUND");
    check(
      http.json("DELETE", ROOT + "/" + a, 2, null, null),
      404,
      "RESOURCE_NOT_FOUND"
    );
    var list = http.json("GET", ROOT, 2, null, null);
    check(list, 200, null);
    assertThat(list.data().path("items")).isEmpty();
    check(http.json("DELETE", ROOT + "/" + a, 1, null, null), 204, null);
    assertThat(memory(a)).isZero();
    check(getConversation(a, 1), 404, "RESOURCE_NOT_FOUND");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_ai_conversation_turn t JOIN t_ai_conversation c ON c.id=t.conversation_id WHERE c.public_id=?",
        Integer.class,
        a
      )
    ).isZero();
  }

  @Test
  void sameKeyReplayDoesNotCallModelOrAppendMemoryAgain() throws Exception {
    String id = createConversation(1),
      k = key();
    var a = send(id, 1, "0", "怠速抖动", k);
    check(a, 200, null);
    int calls = provider.requests.size();
    var b = send(id, 1, "0", "怠速抖动", k);
    check(b, 200, null);
    assertThat(b.data()).isEqualTo(a.data());
    assertThat(provider.requests).hasSize(calls);
    assertThat(memory(id)).isEqualTo(2);
    check(send(id, 1, "0", "更换内容", k), 409, "IDEMPOTENCY_KEY_REUSED");
    check(send(id, 1, "0", "旧版本", key()), 409, "RESOURCE_VERSION_CONFLICT");
  }

  @Test
  void timeoutOrModelFailureDoesNotAdvanceFactsOrMemoryAndSameKeyCanRetry()
    throws Exception {
    String id = createConversation(1),
      k = key();
    provider.fail = true;
    check(send(id, 1, "0", "跑了3万公里", k), 503, "AI_UNAVAILABLE");
    assertThat(memory(id)).isZero();
    assertThat(
      getConversation(id, 1).data().path("revision").asString()
    ).isEqualTo("0");
    provider.fail = false;
    check(send(id, 1, "0", "跑了3万公里", k), 200, null);
    assertThat(memory(id)).isEqualTo(2);
    assertThat(
      db.queryForObject(
        "SELECT attempts FROM t_ai_conversation_turn WHERE request_key=?",
        Integer.class,
        k
      )
    ).isEqualTo(2);
  }

  @Test
  void fabricatedFactIsRejectedAndNeverBecomesDurableContext()
    throws Exception {
    String id = createConversation(1);
    check(send(id, 1, "0", "伪造里程", key()), 503, "AI_UNAVAILABLE");
    assertThat(memory(id)).isZero();
    assertThat(
      getConversation(id, 1).data().path("facts").path("mileage").isNull()
    ).isTrue();
  }

  @Test
  void toolCallingExecutesReadOnlyQuoteDraftWithoutPublishing()
    throws Exception {
    String id = createConversation(1);
    var r = send(id, 1, "0", "报价草稿", key());
    check(r, 200, null);
    assertThat(r.data().path("toolCalls").get(0).asString()).isEqualTo(
      "quoteDraft"
    );
    var quote = r.data().path("quoteDraft");
    assertThat(quote.path("requiresTechnicianReview").asBoolean()).isTrue();
    assertThat(quote.path("estimatedTotal").asString()).isEqualTo(
      db
        .queryForObject(
          "SELECT price FROM t_service_item WHERE id=1",
          java.math.BigDecimal.class
        )
        .setScale(2)
        .toPlainString()
    );
    assertThat(provider.requests.size()).isEqualTo(2);
    assertThat(provider.requests.getLast().toString()).contains(
      "\"role\":\"tool\"",
      "estimatedTotal"
    );
    assertThat(
      db.queryForObject("SELECT COUNT(*) FROM t_order_quote", Integer.class)
    ).isZero();
  }

  @Test
  void toolsOnlySeeCurrentUsersVehicleAndOwnedOrder() throws Exception {
    long order = create(0, "05:00");
    String owner = createConversation(1),
      other = createConversation(2);
    check(send(owner, 1, "0", "查订单 " + order, key()), 200, null);
    assertThat(provider.requests.getLast().toString()).contains("RESERVED");
    check(send(other, 2, "0", "查订单 " + order, key()), 200, null);
    assertThat(provider.requests.getLast().toString())
      .contains("RESOURCE_NOT_FOUND")
      .doesNotContain("RESERVED");
    check(send(owner, 1, "1", "查我的车", key()), 200, null);
    assertThat(provider.requests.getLast().toString())
      .contains("plateMasked")
      .doesNotContain("plateMaskSource", "ownerId");
  }

  @Test
  void toolLoopIsBoundedAndDoesNotCommitPartialTurn() throws Exception {
    String id = createConversation(1);
    check(send(id, 1, "0", "工具循环", key()), 503, "AI_UNAVAILABLE");
    assertThat(provider.requests.size()).isLessThanOrEqualTo(4);
    assertThat(memory(id)).isZero();
  }

  @Test
  void onlyOneLiveTurnAndExpiredWorkerCannotOverwriteNewMemory()
    throws Exception {
    String id = createConversation(1),
      k = key(),
      other = key(),
      busy = key();
    var pool = Executors.newSingleThreadExecutor();
    try {
      var old = pool.submit(() -> send(id, 1, "0", "等待旧请求", k));
      assertThat(provider.entered.await(4, TimeUnit.SECONDS)).isTrue();
      check(send(id, 1, "0", "并发消息", busy), 429, "AI_QUEUE_FULL");
      db.update(
        "UPDATE t_ai_conversation SET lease_until=UTC_TIMESTAMP(3) WHERE public_id=?",
        id
      );
      check(send(id, 1, "0", "跑了3万公里", other), 200, null);
      provider.release.countDown();
      check(old.get(5, TimeUnit.SECONDS), 409, "RESOURCE_VERSION_CONFLICT");
      assertThat(memory(id)).isEqualTo(2);
      assertThat(
        db.queryForList(
          "SELECT content FROM spring_ai_chat_memory WHERE conversation_id=?",
          String.class,
          id
        )
      )
        .doesNotContain("等待旧请求")
        .contains("跑了3万公里");
    } finally {
      provider.release.countDown();
      pool.shutdownNow();
    }
  }
}
