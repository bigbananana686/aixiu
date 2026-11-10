package com.autocare.server.conversation;

import com.autocare.ai.conversation.*;
import com.autocare.server.ai.BookingAiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ConversationConfiguration {

  @Bean
  @ConditionalOnMissingBean(ConversationAssistant.class)
  ConversationAssistant conversationAssistant(
    BookingAiProperties p,
    JdbcTemplate jdbc,
    PlatformTransactionManager tx,
    Environment env
  ) {
    if (!p.enabled()) return new SpringConversationAssistant(null, jdbc, tx);
    String secret = env.getProperty(
      "MODEL_API_KEY",
      env.getProperty("DEEPSEEK_API_KEY")
    );
    if (secret == null || secret.isBlank()) throw new IllegalStateException(
      "Conversation AI enabled but MODEL_API_KEY missing"
    );
    return new SpringConversationAssistant(
      SpringConversationAssistant.deepSeek(
        p.baseUrl(),
        secret,
        p.model(),
        p.timeout()
      ),
      jdbc,
      tx
    );
  }
}
