package com.autocare.server.order.quote;

import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.order.lifecycle.OrderLifecycleMapper;
import com.autocare.server.order.lifecycle.OrderLifecycleService;
import com.autocare.server.security.AdminAccess;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.MultiValueMap;

@Service
public class QuoteQueryService {

  private final com.autocare.server.analysis.AnalysisResults analyses;
  private final QuoteMapper quotes;
  private final OrderLifecycleMapper orders;
  private final OrderLifecycleService lifecycle;
  private final AdminAccess admin;

  public QuoteQueryService(
    QuoteMapper quotes,
    OrderLifecycleMapper orders,
    OrderLifecycleService lifecycle,
    com.autocare.server.analysis.AnalysisResults analyses,
    AdminAccess admin
  ) {
    this.quotes = quotes;
    this.analyses = analyses;
    this.orders = orders;
    this.lifecycle = lifecycle;
    this.admin = admin;
  }

  public record Page(
    List<QuoteView> items,
    int page,
    int pageSize,
    long total
  ) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(
    long actor,
    String orderId,
    MultiValueMap<String, String> query
  ) {
    Role role = lifecycle.currentRole(actor, null);
    long id = QuoteInputs.pathId(orderId, "orderId");
    OrderLifecycleService.requireVisible(orders.find(id), actor, role);
    return pageQuotes(actor, id, query);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page listForAdmin(long actor, String orderId, MultiValueMap<String, String> query) {
    admin.require(actor);
    long id = QuoteInputs.pathId(orderId, "orderId");
    if (orders.find(id) == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    return pageQuotes(actor, id, query);
  }

  private Page pageQuotes(long actor, long id, MultiValueMap<String, String> query) {
    for (var e : query.entrySet()) {
      if (
        !Set.of("page", "pageSize").contains(e.getKey())
      ) throw ApiException.invalid(
        FieldErrors.notAllowed("/" + e.getKey(), "不接受的查询参数")
      );
      if (e.getValue().size() != 1) throw ApiException.invalid(
        FieldErrors.duplicate("/" + e.getKey(), "不可重复")
      );
    }
    int p = page(query.getFirst("page"), "page", 1, 10000),
      size = page(query.getFirst("pageSize"), "pageSize", 20, 100);
    return new Page(
      quotes
        .list(id, size, (p - 1) * size)
        .stream()
        .map(q ->
          QuoteView.from(q, quotes.items(q.getId())).withAnalysis(
            analyses.summary(actor, id, q)
          )
        )
        .toList(),
      p,
      size,
      quotes.count(id)
    );
  }

  private int page(String v, String field, int defaultValue, int max) {
    if (v == null) return defaultValue;
    try {
      if (!v.matches("[1-9][0-9]{0,8}")) throw new NumberFormatException();
      int n = Integer.parseInt(v);
      if (n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/" + field, "分页超出范围")
      );
    }
  }
}
