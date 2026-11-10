package com.autocare.server.order.query;

import com.autocare.server.order.quote.QuoteQueryService;
import com.autocare.server.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {
  private final OrderQueryService orders;
  private final QuoteQueryService quotes;

  public AdminOrderController(OrderQueryService orders, QuoteQueryService quotes) {
    this.orders = orders;
    this.quotes = quotes;
  }

  @GetMapping
  public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query,
                                HttpServletRequest request) {
    return reply(request, orders.listForAdmin(Long.parseLong(jwt.getSubject()), query));
  }

  @GetMapping("/{orderId}")
  public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
                                  HttpServletRequest request) {
    return reply(request, orders.detailForAdmin(Long.parseLong(jwt.getSubject()), orderId));
  }

  @GetMapping("/{orderId}/quotes")
  public ResponseEntity<?> quotes(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
                                  @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
    return reply(request, quotes.listForAdmin(Long.parseLong(jwt.getSubject()), orderId, query));
  }

  private ResponseEntity<?> reply(HttpServletRequest request, Object data) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(request, data));
  }
}
