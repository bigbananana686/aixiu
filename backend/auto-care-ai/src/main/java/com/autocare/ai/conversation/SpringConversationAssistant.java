package com.autocare.ai.conversation;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.memory.repository.jdbc.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** 标准Memory/Advisor/JDBC持久化；网络调用使用逐请求临时记忆，防止失败/旧请求污染已提交记忆。 */
public final class SpringConversationAssistant
  implements ConversationAssistant
{

  private final ChatModel model;
  private final JdbcChatMemoryRepository repository;
  private final boolean enabled;
  private final JsonMapper json = JsonMapper.builder()
    .enable(
      tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
    )
    .enable(
      tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES
    )
    .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
    .build();

  public SpringConversationAssistant(
    ChatModel model,
    JdbcTemplate jdbc,
    PlatformTransactionManager manager
  ) {
    this.model = model == null ? null : new ToolContentCompatibleModel(model);
    enabled = model != null;
    // 保留官方MySQL方言，仅适配项目lower_case_table_names=0的全小写表名。
    var dialect = new MysqlChatMemoryRepositoryDialect() {
      private String lower(String sql) {
        return sql.replace("SPRING_AI_CHAT_MEMORY", "spring_ai_chat_memory");
      }

      public String getSelectMessagesSql() {
        return lower(super.getSelectMessagesSql());
      }

      public String getInsertMessageSql() {
        return lower(super.getInsertMessageSql());
      }

      public String getSelectConversationIdsSql() {
        return lower(super.getSelectConversationIdsSql());
      }

      public String getDeleteMessagesSql() {
        return lower(super.getDeleteMessagesSql());
      }
    };
    repository = JdbcChatMemoryRepository.builder()
      .jdbcTemplate(jdbc)
      .dialect(dialect)
      .transactionManager(manager)
      .build();
  }

  public static ChatModel deepSeek(
    URI url,
    String key,
    String name,
    Duration timeout
  ) {
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    var api = org.springframework.ai.deepseek.api.DeepSeekApi.builder()
      .baseUrl(url.toString())
      .apiKey(key)
      .restClientBuilder(
        org.springframework.web.client.RestClient.builder().requestFactory(
          factory
        )
      )
      .build();
    return org.springframework.ai.deepseek.DeepSeekChatModel.builder()
      .deepSeekApi(api)
      .options(
        org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
          .model(name)
          .disableThinking()
          .maxTokens(4096)
          .build()
      )
      .retryTemplate(
        new org.springframework.core.retry.RetryTemplate(
          org.springframework.core.retry.RetryPolicy.builder()
            .maxRetries(0)
            .build()
        )
      )
      .build();
  }

  private record Result(
    String conversationId,
    Reply reply,
    List<Message> messages,
    List<String> toolEvidence,
    List<String> toolCalls
  ) implements Prepared {}

  public Prepared prepare(
    String id,
    String trustedFacts,
    String message,
    ReadTools port
  ) {
    if (!enabled) throw new IllegalStateException("Conversation AI disabled");
    if (
      TransactionSynchronizationManager.isActualTransactionActive()
    ) throw new IllegalStateException(
      "Model invocation must be outside transaction"
    );
    var staged = MessageWindowChatMemory.builder().maxMessages(12).build();
    var history = repository.findByConversationId(id);
    if (!history.isEmpty()) staged.add(id, history);
    var tools = new ScopedTools(port);
    var converter = new BeanOutputConverter<>(Reply.class, json);
    String system;
    try {
      system = new org.springframework.core.io.ClassPathResource(
        "prompts/intake-conversation-v1.st"
      ).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Conversation prompt missing", e);
    }
    int first = org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
    var toolAdvisor = ToolCallingAdvisor.builder()
      .advisorOrder(first + 20)
      .toolExecutionEligibilityChecker(
        response ->
          response.hasToolCalls() &&
          tools.calls.size() +
            response
              .getResults()
              .stream()
              .mapToInt(g -> g.getOutput().getToolCalls().size())
              .sum() <=
            3
      )
      .build();
    var answer = ChatClient.builder(model)
      .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(staged)
          .order(first + 10)
          .build(),
        toolAdvisor
      )
      .build()
      .prompt()
      .system(s -> s.text(system).param("facts", trustedFacts))
      .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
      .tools(tools)
      .user(message)
      .call()
      .entity(converter);
    if (
      answer == null ||
      answer.reply() == null ||
      answer.reply().isBlank() ||
      answer.reply().length() > 1200 ||
      answer.updates() == null ||
      answer.updates().size() > 20
    ) throw new IllegalArgumentException("Invalid conversation response");
    // 持久记忆保留人类可读回复；结构化业务事实另由server验证并与本轮记忆一起提交。
    var messages = new ArrayList<Message>(staged.get(id));
    if (
      !messages.isEmpty() &&
      messages.getLast().getMessageType() == MessageType.ASSISTANT
    ) messages.set(messages.size() - 1, new AssistantMessage(answer.reply()));
    return new Result(
      id,
      answer,
      List.copyOf(messages),
      List.copyOf(tools.evidence),
      List.copyOf(tools.calls)
    );
  }

  public void commit(Prepared prepared) {
    if (
      !TransactionSynchronizationManager.isActualTransactionActive()
    ) throw new IllegalStateException(
      "Memory commit requires fenced transaction"
    );
    if (
      !(prepared instanceof Result result)
    ) throw new IllegalArgumentException("Foreign memory result");
    repository.saveAll(result.conversationId(), result.messages());
  }

  public void clear(String id) {
    repository.deleteByConversationId(id);
  }

  /** DeepSeek 2.0.1重发工具轮次时要求assistant文本非null；保留工具调用，只规范化空文本。 */
  private record ToolContentCompatibleModel(
    ChatModel delegate
  ) implements ChatModel {
    public org.springframework.ai.chat.model.ChatResponse call(
      org.springframework.ai.chat.prompt.Prompt prompt
    ) {
      var messages = prompt
        .getInstructions()
        .stream()
        .map(m -> {
          if (
            m instanceof AssistantMessage a &&
            a.getText() == null &&
            !a.getToolCalls().isEmpty()
          ) return (Message) AssistantMessage.builder()
            .content("")
            .properties(a.getMetadata())
            .toolCalls(a.getToolCalls())
            .media(a.getMedia())
            .build();
          return m;
        })
        .toList();
      return delegate.call(
        new org.springframework.ai.chat.prompt.Prompt(
          messages,
          prompt.getOptions()
        )
      );
    }

    public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
      return delegate.getOptions();
    }

    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
      return delegate.getDefaultOptions();
    }
  }

  /** 工具执行由Spring AI ToolCallingManager负责；身份来自服务端闭包，模型不能指定actor。 */
  public static final class ScopedTools {

    private final ReadTools port;
    final List<String> evidence = new ArrayList<>(),
      calls = new ArrayList<>();

    public ScopedTools(ReadTools port) {
      this.port = port;
    }

    private String call(
      String name,
      java.util.function.Supplier<String> action
    ) {
      if (
        Thread.currentThread().isInterrupted() || calls.size() >= 3
      ) throw new IllegalStateException("Tool budget exceeded");
      calls.add(name);
      String result = action.get();
      if (
        result == null || result.length() > 12000
      ) throw new IllegalStateException("Tool result budget exceeded");
      evidence.add(result);
      return result;
    }

    @Tool(
      description = "查询当前用户自己的车辆，仅返回车辆ID、车型、里程和脱敏车牌"
    )
    public String myVehicles() {
      return call("myVehicles", port::myVehicles);
    }

    @Tool(description = "查询当前启用的服务目录、价格、工时和目录版本")
    public String serviceCatalog() {
      return call("serviceCatalog", port::serviceCatalog);
    }

    @Tool(description = "查询当前用户有权访问的订单摘要，不能查别人的订单")
    public String orderSummary(String orderId) {
      return call("orderSummary", () -> port.orderSummary(orderId));
    }

    @Tool(
      description = "按启用服务目录计算报价参考草稿，仅供人工核对，不发布或批准报价"
    )
    public String quoteDraft(List<String> serviceItemIds) {
      return call("quoteDraft", () -> port.quoteDraft(serviceItemIds));
    }
  }
}
