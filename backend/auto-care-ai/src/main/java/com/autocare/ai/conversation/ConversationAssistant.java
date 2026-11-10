package com.autocare.ai.conversation;

import java.util.List;

/** server只使用业务端口；会话记忆、Advisor和工具循环的Spring AI类型留在ai模块。 */
public interface ConversationAssistant {
  record Update(String field, String value, String evidence) {}

  record Reply(String reply, List<Update> updates) {}

  interface Prepared {
    Reply reply();
    List<String> toolEvidence();
    List<String> toolCalls();
  }

  interface ReadTools {
    String myVehicles();
    String serviceCatalog();
    String orderSummary(String orderId);
    String quoteDraft(List<String> serviceItemIds);
  }

  Prepared prepare(
    String conversationId,
    String trustedFacts,
    String message,
    ReadTools tools
  );
  /** 仅在server已验证本次领取token的结果事务内调用；不调用模型。 */
  void commit(Prepared prepared);
  void clear(String conversationId);
}
