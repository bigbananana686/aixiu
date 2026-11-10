package com.autocare.server.order.extension;

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
public class ExtensionController {

  private final ExtensionService service;
  private final OrderLifecycleService lifecycle;

  public ExtensionController(
    ExtensionService service,
    OrderLifecycleService lifecycle
  ) {
    this.service = service;
    this.lifecycle = lifecycle;
  }

  @ModelAttribute
  public void authorize(@AuthenticationPrincipal Jwt jwt) {
    lifecycle.currentRole(Long.parseLong(jwt.getSubject()), Role.TECH);
  }

  @PostMapping("/{orderId}/extension")
  public ResponseEntity<ApiResponse<JsonNode>> execute(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String orderId,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String k,
    HttpServletRequest r
  ) {
    return CommandResponses.toEntity(
      service.execute(
        Long.parseLong(jwt.getSubject()),
        orderId,
        body,
        k,
        RequestIdFilter.currentRequestId(r)
      ),
      r
    );
  }
}
