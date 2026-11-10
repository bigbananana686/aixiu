package com.autocare.server.conversation;

import com.autocare.server.command.CommandResponses;
import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/ai/conversations")
public class ConversationController {

  private final ConversationService service;

  public ConversationController(ConversationService service) {
    this.service = service;
  }

  private long actor(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }

  @ModelAttribute
  public void authorize(@AuthenticationPrincipal Jwt jwt) {
    service.require(actor(jwt));
  }

  @PostMapping
  public ResponseEntity<?> create(
    @AuthenticationPrincipal Jwt jwt,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest req
  ) {
    return CommandResponses.toEntity(
      service.create(
        actor(jwt),
        body,
        key,
        RequestIdFilter.currentRequestId(req)
      ),
      req
    );
  }

  @GetMapping
  public ResponseEntity<?> list(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam MultiValueMap<String, String> q,
    HttpServletRequest req
  ) {
    return ok(req, service.list(actor(jwt), q));
  }

  @GetMapping("/{conversationId}")
  public ResponseEntity<?> get(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String conversationId,
    HttpServletRequest req
  ) {
    return ok(req, service.get(actor(jwt), conversationId));
  }

  @PostMapping("/{conversationId}/messages")
  public ResponseEntity<?> send(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String conversationId,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest req
  ) {
    return CommandResponses.toEntity(
      service.send(
        actor(jwt),
        conversationId,
        body,
        key,
        RequestIdFilter.currentRequestId(req)
      ),
      req
    );
  }

  @DeleteMapping("/{conversationId}")
  public ResponseEntity<?> delete(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String conversationId
  ) {
    service.delete(actor(jwt), conversationId);
    return ResponseEntity.noContent()
      .cacheControl(CacheControl.noStore())
      .build();
  }

  private ResponseEntity<?> ok(HttpServletRequest req, Object data) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(new ApiResponse<>(RequestIdFilter.currentRequestId(req), data));
  }
}
