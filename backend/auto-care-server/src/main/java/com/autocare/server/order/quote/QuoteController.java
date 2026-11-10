package com.autocare.server.order.quote;

import com.autocare.common.identity.Role;
import com.autocare.server.command.CommandResponses;
import com.autocare.server.order.lifecycle.OrderLifecycleService;
import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/quotes")
public class QuoteController {

  private final QuoteService service;
  private final QuoteQueryService queries;
  private final OrderLifecycleService lifecycle;

  public QuoteController(
    QuoteService service,
    QuoteQueryService queries,
    OrderLifecycleService lifecycle
  ) {
    this.service = service;
    this.queries = queries;
    this.lifecycle = lifecycle;
  }

  @ModelAttribute
  public void authorize(
    @AuthenticationPrincipal Jwt jwt,
    HttpServletRequest request
  ) {
    Role role = null;
    if ("POST".equals(request.getMethod())) role = request
      .getRequestURI()
      .matches(".*/quotes(?:/[^/]+/withdraw)?")
      ? Role.TECH
      : Role.OWNER;
    lifecycle.currentRole(Long.parseLong(jwt.getSubject()), role);
  }

  @GetMapping
  public ResponseEntity<ApiResponse<QuoteQueryService.Page>> list(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String orderId,
    @RequestParam MultiValueMap<String, String> query,
    HttpServletRequest request
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(
        new ApiResponse<>(
          RequestIdFilter.currentRequestId(request),
          queries.list(Long.parseLong(jwt.getSubject()), orderId, query)
        )
      );
  }

  @PostMapping
  public ResponseEntity<ApiResponse<JsonNode>> submit(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String orderId,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest request
  ) {
    return CommandResponses.toEntity(
      service.submit(
        Long.parseLong(jwt.getSubject()),
        orderId,
        body,
        key,
        RequestIdFilter.currentRequestId(request)
      ),
      request
    );
  }

  @PostMapping("/{quoteId}/confirm")
  public ResponseEntity<ApiResponse<JsonNode>> confirm(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String orderId,
    @PathVariable String quoteId,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest request
  ) {
    return decide(jwt, orderId, quoteId, body, key, request, false);
  }

  @PostMapping("/{quoteId}/reject")
  public ResponseEntity<ApiResponse<JsonNode>> reject(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String orderId,
    @PathVariable String quoteId,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest request
  ) {
    return decide(jwt, orderId, quoteId, body, key, request, true);
  }

  @PostMapping("/{quoteId}/withdraw")
  public ResponseEntity<ApiResponse<JsonNode>> withdraw(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String orderId,
    @PathVariable String quoteId,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest request
  ) {
    return CommandResponses.toEntity(
      service.withdraw(
        Long.parseLong(jwt.getSubject()),
        orderId,
        quoteId,
        body,
        key,
        RequestIdFilter.currentRequestId(request)
      ),
      request
    );
  }

  private ResponseEntity<ApiResponse<JsonNode>> decide(
    Jwt jwt,
    String order,
    String quote,
    JsonNode body,
    String key,
    HttpServletRequest request,
    boolean reject
  ) {
    return CommandResponses.toEntity(
      service.decide(
        Long.parseLong(jwt.getSubject()),
        order,
        quote,
        body,
        key,
        RequestIdFilter.currentRequestId(request),
        reject
      ),
      request
    );
  }
}
