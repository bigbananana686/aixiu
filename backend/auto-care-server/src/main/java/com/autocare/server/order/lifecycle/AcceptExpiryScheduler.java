package com.autocare.server.order.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "autocare.order.accept-expiry-enabled", havingValue = "true", matchIfMissing = true)
public class AcceptExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(AcceptExpiryScheduler.class);
    private final AcceptExpiryService service;

    public AcceptExpiryScheduler(AcceptExpiryService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${autocare.order.accept-expiry-delay-ms:30000}",
            initialDelayString = "${autocare.order.accept-expiry-initial-delay-ms:10000}")
    public void run() {
        int changed = service.scan();
        if (changed > 0) log.info("接单到期扫描已提交 cancellations={}", changed);
    }
}
