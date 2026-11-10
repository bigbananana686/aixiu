package com.autocare.server.order.quote;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
  name = "autocare.order.quote-expiry-enabled",
  havingValue = "true",
  matchIfMissing = true
)
public class QuoteExpiryScheduler {

  private final QuoteExpiryService service;

  public QuoteExpiryScheduler(QuoteExpiryService service) {
    this.service = service;
  }

  @Scheduled(
    fixedDelayString = "${autocare.order.quote-expiry-delay-ms:30000}",
    initialDelayString = "${autocare.order.quote-expiry-initial-delay-ms:10000}"
  )
  public void run() {
    service.scan();
  }
}
