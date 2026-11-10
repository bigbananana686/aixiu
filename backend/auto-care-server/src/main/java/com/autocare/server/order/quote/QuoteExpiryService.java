package com.autocare.server.order.quote;

import com.autocare.server.order.OrderRow;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.order.lifecycle.OrderLifecycleMapper;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuoteExpiryService {

  private static final Logger log = LoggerFactory.getLogger(
    QuoteExpiryService.class
  );
  private final QuoteMapper quotes;
  private final OrderLifecycleMapper orders;
  private final OrderMapper resources;
  private final QuoteService service;
  private final TransactionTemplate tx;

  public QuoteExpiryService(
    QuoteMapper quotes,
    OrderLifecycleMapper orders,
    OrderMapper resources,
    QuoteService service,
    PlatformTransactionManager manager
  ) {
    this.quotes = quotes;
    this.orders = orders;
    this.resources = resources;
    this.service = service;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public int scan() {
    int changed = 0;
    for (var q : quotes.expired())
      try {
        if (expire(q.getOrderId(), q.getId(), q.getVersion())) changed++;
      } catch (DataAccessException e) {
        log.warn(
          "报价到期失败 orderId={} quoteId={} exception={}; 下轮重试",
          q.getOrderId(),
          q.getId(),
          e.getClass().getSimpleName()
        );
      }
    return changed;
  }

  public boolean expire(long order, long quote, int version) {
    OrderRow reference = orders.find(order);
    if (reference == null) return false;
    return Boolean.TRUE.equals(
      tx.execute(status -> {
        orders.lockTechnician(reference.getTechnicianId());
        OrderRow r = orders.lockOrder(order);
        QuoteRow q = quotes.lock(order, quote);
        if (
          r == null ||
          q == null ||
          !"QUOTE_PENDING".equals(r.getState()) ||
          !Objects.equals(r.getPendingQuoteId(), quote) ||
          q.getVersion() != version ||
          !"PENDING".equals(q.getStatus())
        ) return false;
        var now = resources.dbNowUtc();
        if (now.isBefore(q.getExpiresAt())) return false;
        QuoteService.requireOne(
          quotes.terminate(
            quote,
            "EXPIRED",
            null,
            null,
            "quote confirmation deadline reached"
          )
        );
        service.writeOrder(
          r,
          "EXTRA".equals(q.getPurpose()) ? "SERVICING" : "ARRIVED",
          r.getApprovedQuoteId(),
          null,
          r.getCommittedEnd(),
          r.getCommittedEnd(),
          "EXPIRE_QUOTE",
          q,
          orders.expiryActor(),
          null,
          UUID.randomUUID().toString(),
          now
        );
        return true;
      })
    );
  }
}
