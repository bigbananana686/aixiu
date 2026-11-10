package com.autocare.server.ai;

import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/ai/booking-drafts")
public class BookingDraftController {

  private final BookingDraftService service;

  public BookingDraftController(BookingDraftService service) {
    this.service = service;
  }

  @ModelAttribute
  public void authorize(@AuthenticationPrincipal Jwt jwt) {
    service.requireOwner(Long.parseLong(jwt.getSubject()));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> parse(
    @AuthenticationPrincipal Jwt jwt,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest request
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(
        new ApiResponse<>(
          RequestIdFilter.currentRequestId(request),
          service.parse(Long.parseLong(jwt.getSubject()), body, key)
        )
      );
  }
}
