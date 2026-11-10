package com.autocare.ai.analysis;

import com.autocare.ai.knowledge.Evidence;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

public final class SpringQuoteAnalyzer implements QuoteAnalyzer {

  private final ChatClient client;
  private final String modelId, system;
  private final JsonMapper json = JsonMapper.builder()
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

  public SpringQuoteAnalyzer(ChatModel model, String modelId) {
    client = ChatClient.create(model);
    this.modelId = modelId;
    try {
      system = new ClassPathResource(
        "prompts/quote-analysis-v1.st"
      ).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Missing analysis prompt", e);
    }
  }

  public static QuoteAnalyzer deepSeek(
    URI url,
    String secret,
    String modelId,
    Duration timeout
  ) {
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    var api = org.springframework.ai.deepseek.api.DeepSeekApi.builder()
      .baseUrl(url.toString())
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
    return new SpringQuoteAnalyzer(model, modelId);
  }

  public Answer analyze(Context quote, List<Evidence> candidates) {
    var converter = new BeanOutputConverter<>(Result.class, json);
    var response = client
      .prompt()
      .system(s -> s.text(system).param("format", converter.getFormat()))
      .user(
        json.writeValueAsString(
          Map.of("quote", quote, "authorizedEvidence", candidates)
        )
      )
      .call()
      .chatResponse();
    Result result;
    try {
      String raw = response.getResult().getOutput().getText();
      if (
        !json.readTree(raw).isObject()
      ) throw new AnalysisValidator.InvalidAnalysisException();
      result = converter.convert(raw);
    } catch (RuntimeException e) {
      throw new AnalysisValidator.InvalidAnalysisException();
    }
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
    return new Answer(
      result,
      modelId,
      usage == null ? null : usage.getPromptTokens(),
      usage == null ? null : usage.getCompletionTokens(),
      usage == null ? null : usage.getTotalTokens()
    );
  }
}
