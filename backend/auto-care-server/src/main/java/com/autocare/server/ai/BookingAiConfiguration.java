package com.autocare.server.ai;

import com.autocare.ai.booking.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(BookingAiProperties.class)
public class BookingAiConfiguration {

  @Bean
  @ConditionalOnMissingBean(BookingDraftParser.class)
  BookingDraftParser bookingDraftParser(
    BookingAiProperties p,
    Environment env
  ) {
    if (!p.enabled()) return (text, now, zone) -> {
      throw new IllegalStateException("Booking AI disabled");
    };
    String secret = env.getProperty(
      "MODEL_API_KEY",
      env.getProperty("DEEPSEEK_API_KEY")
    );
    if (secret == null || secret.isBlank()) throw new IllegalStateException(
      "Booking AI enabled but MODEL_API_KEY missing"
    );
    return SpringBookingDraftParser.deepSeek(
      p.baseUrl(),
      secret,
      p.model(),
      p.timeout()
    );
  }
}
