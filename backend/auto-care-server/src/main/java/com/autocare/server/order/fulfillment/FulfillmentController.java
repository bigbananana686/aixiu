package com.autocare.server.order.fulfillment;

import com.autocare.common.identity.Role;
import com.autocare.server.command.CommandResponses;
import com.autocare.server.order.lifecycle.OrderLifecycleService;
import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/orders")
public class FulfillmentController {

  private final FulfillmentService service;
  private final OrderLifecycleService lifecycle;

  public FulfillmentController(
    FulfillmentService service,
    OrderLifecycleService lifecycle
  ) {
    this.service = service;
    this.lifecycle = lifecycle;
  }

  @ModelAttribute
  public void authorize(
    @AuthenticationPrincipal Jwt jwt,
    HttpServletRequest request
  ) {
    lifecycle.currentRole(
      Long.parseLong(jwt.getSubject()),
      request.getRequestURI().endsWith("/close") ? Role.OWNER : Role.TECH
    );
  }

  @PostMapping("/{orderId}/{action:start|complete|close}")
  public ResponseEntity<ApiResponse<JsonNode>> execute(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String orderId,
    @PathVariable String action,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest request
  ) {
    return CommandResponses.toEntity(
      service.execute(
        Long.parseLong(jwt.getSubject()),
        orderId,
        action,
        body,
        key,
        RequestIdFilter.currentRequestId(request)
      ),
      request
    );
  }
}
