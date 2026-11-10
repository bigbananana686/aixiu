package com.autocare.server.conversation;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.conversation.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ConversationMemoryTest {

  @Autowired
  JdbcTemplate db;

  @Autowired
  PlatformTransactionManager manager;

  static final ConversationAssistant.ReadTools NO_TOOLS =
    new ConversationAssistant.ReadTools() {
      public String myVehicles() {
        return "[]";
      }

      public String serviceCatalog() {
        return "[]";
      }

      public String orderSummary(String id) {
        return "{}";
      }

      public String quoteDraft(List<String> ids) {
        return "{}";
      }
    };

  SpringConversationAssistant fresh(ControlledConversationProvider p) {
    return new SpringConversationAssistant(
      SpringConversationAssistant.deepSeek(
        p.url(),
        "controlled-key",
        "controlled-chat",
        Duration.ofSeconds(2)
      ),
      db,
      manager
    );
  }

  @Test
  void newAssistantAndRepositoryReadEarlierMessagesFromMysql()
    throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class)).startsWith(
      "auto_care_g104_"
    );
    String id = UUID.randomUUID().toString();
    try (var provider = new ControlledConversationProvider()) {
      var a = fresh(provider);
      var one = a.prepare(id, "{}", "我是COROLLA_2020", NO_TOOLS);
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM spring_ai_chat_memory WHERE conversation_id=?",
          Integer.class,
          id
        )
      ).isZero();
      new TransactionTemplate(manager).executeWithoutResult(s -> a.commit(one));
      var reopened = fresh(provider);
      reopened.prepare(id, "{}", "补充3万公里", NO_TOOLS);
      assertThat(provider.requests.getLast().toString()).contains(
        "我是COROLLA_2020",
        "补充3万公里"
      );
    } finally {
      db.update(
        "DELETE FROM spring_ai_chat_memory WHERE conversation_id=?",
        id
      );
    }
  }

  @Test
  void rollbackOfResultTransactionRollsBackJdbcMemory() throws Exception {
    String id = UUID.randomUUID().toString();
    try (var provider = new ControlledConversationProvider()) {
      var a = fresh(provider);
      var staged = a.prepare(id, "{}", "怠速抖动", NO_TOOLS);
      assertThatThrownBy(() ->
        new TransactionTemplate(manager).executeWithoutResult(s -> {
          a.commit(staged);
          throw new IllegalStateException("controlled failure");
        })
      ).isInstanceOf(IllegalStateException.class);
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM spring_ai_chat_memory WHERE conversation_id=?",
          Integer.class,
          id
        )
      ).isZero();
    } finally {
      db.update(
        "DELETE FROM spring_ai_chat_memory WHERE conversation_id=?",
        id
      );
    }
  }

  @Test
  void controlledToolProtocolReturnsFinalResponse() throws Exception {
    try (var provider = new ControlledConversationProvider()) {
      var a = fresh(provider);
      var result = a.prepare(
        UUID.randomUUID().toString(),
        "{}",
        "报价草稿",
        NO_TOOLS
      );
      assertThat(result.toolCalls()).containsExactly("quoteDraft");
      assertThat(provider.requests).hasSize(2);
    }
  }
}
