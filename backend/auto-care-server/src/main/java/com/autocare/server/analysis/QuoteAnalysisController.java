package com.autocare.server.analysis;

import com.autocare.server.command.CommandResponses;
import com.autocare.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class QuoteAnalysisController {

  private final QuoteAnalysisService service;

  public QuoteAnalysisController(QuoteAnalysisService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/orders/{orderId}/quote-analysis")
  public ResponseEntity<?> submit(
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
}
