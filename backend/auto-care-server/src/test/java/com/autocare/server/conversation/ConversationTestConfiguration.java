package com.autocare.server.conversation;

import com.autocare.ai.conversation.*;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@TestConfiguration(proxyBeanMethods = false)
public class ConversationTestConfiguration {

  @Bean(destroyMethod = "close")
  public ControlledConversationProvider conversationProvider()
    throws Exception {
    return new ControlledConversationProvider();
  }

  @Bean
  public ConversationAssistant controlledAssistant(
    ControlledConversationProvider p,
    JdbcTemplate jdbc,
    PlatformTransactionManager tx
  ) {
    return new SpringConversationAssistant(
      SpringConversationAssistant.deepSeek(
        p.url(),
        "controlled-key",
        "controlled-chat",
        Duration.ofSeconds(4)
      ),
      jdbc,
      tx
    );
  }
}
