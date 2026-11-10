package com.autocare.ai.booking;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;

public final class SpringBookingDraftParser implements BookingDraftParser {

  private final ChatClient client;
  private final String modelId, system;

  private SpringBookingDraftParser(ChatModel model, String modelId) {
    this.client = ChatClient.create(model);
    this.modelId = modelId;
    try {
      system = new ClassPathResource(
        "prompts/booking-draft-v1.st"
      ).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Missing booking prompt", e);
    }
  }

  /** 厂商 SDK 留在 ai 模块，server 只传配置和使用稳定接口。 */
  public static BookingDraftParser deepSeek(
    java.net.URI baseUrl,
    String secret,
    String modelId,
    java.time.Duration timeout
  ) {
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    var api = org.springframework.ai.deepseek.api.DeepSeekApi.builder()
      .baseUrl(baseUrl.toString())
      .apiKey(secret)
      .restClientBuilder(
        org.springframework.web.client.RestClient.builder().requestFactory(
          factory
        )
      )
      .build();
    var model = org.springframework.ai.deepseek.DeepSeekChatModel.builder()
      .deepSeekApi(api)
      .options(
        org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
          .model(modelId)
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
    return new SpringBookingDraftParser(model, modelId);
  }

  public Parsed parse(String text, Instant now, String timeZone) {
    var strict = tools.jackson.databind.json.JsonMapper.builder()
      .enable(
        tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
      )
      .enable(
        tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES
      )
      .enable(
        tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES
      )
      .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
      .build();
    var converter = new BeanOutputConverter<>(BookingDraft.class, strict);
    // 明确区分用户原话与系统约束，不启用 Tools 或 ChatMemory。
    var response = client
      .prompt()
      .system(s ->
        s
          .text(system)
          .param("now", now.toString())
          .param("zone", timeZone)
          .param("format", converter.getFormat())
      )
      .user(text)
      .call()
      .chatResponse();
    BookingDraft draft;
    try {
      String raw = response.getResult().getOutput().getText();
      if (
        !strict.readTree(raw).isObject()
      ) throw new DraftValidationException();
      draft = converter.convert(raw);
    } catch (RuntimeException e) {
      throw new DraftValidationException();
    }
    DraftValidator.validate(draft, text, now);
    var usage = response.getMetadata().getUsage();
    if (
      usage instanceof org.springframework.ai.chat.metadata.EmptyUsage ||
      (usage != null && usage.getNativeUsage() == null)
    ) usage = null;
    if (
      usage != null &&
      usage.getNativeUsage() instanceof
        org.springframework.ai.deepseek.api.DeepSeekApi.Usage nativeUsage &&
      (nativeUsage.promptTokens() == null ||
        nativeUsage.completionTokens() == null ||
        nativeUsage.totalTokens() == null)
    ) usage = null;
    String actual = response.getMetadata().getModel();
    return new Parsed(
      draft,
      actual == null || actual.isBlank() ? modelId : actual,
      usage == null ? null : usage.getPromptTokens(),
      usage == null ? null : usage.getCompletionTokens(),
      usage == null ? null : usage.getTotalTokens()
    );
  }
}
