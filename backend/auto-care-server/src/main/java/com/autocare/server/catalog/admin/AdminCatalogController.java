package com.autocare.server.catalog.admin;

import com.autocare.server.command.CommandResponses;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/admin/service-items")
public class AdminCatalogController {

  private final AdminCatalogService service;
  private final AdminAccess access;

  public AdminCatalogController(
    AdminCatalogService service,
    AdminAccess access
  ) {
    this.service = service;
    this.access = access;
  }

  @ModelAttribute
  public void authorize(@AuthenticationPrincipal Jwt jwt) {
    access.require(Long.parseLong(jwt.getSubject()));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<?>> list(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam MultiValueMap<String, String> q,
    HttpServletRequest r
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(
        new ApiResponse<>(
          RequestIdFilter.currentRequestId(r),
          service.list(Long.parseLong(jwt.getSubject()), q)
        )
      );
  }

  @PostMapping
  public ResponseEntity<ApiResponse<JsonNode>> create(
    @AuthenticationPrincipal Jwt jwt,
    @RequestBody JsonNode b,
    @RequestHeader(value = "Idempotency-Key", required = false) String k,
    HttpServletRequest r
  ) {
    return write(jwt, null, b, k, r);
  }

  @PatchMapping("/{serviceItemId}")
  public ResponseEntity<ApiResponse<JsonNode>> update(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String serviceItemId,
    @RequestBody JsonNode b,
    @RequestHeader(value = "Idempotency-Key", required = false) String k,
    HttpServletRequest r
  ) {
    return write(jwt, serviceItemId, b, k, r);
  }

  private ResponseEntity<ApiResponse<JsonNode>> write(
    Jwt jwt,
    String id,
    JsonNode b,
    String k,
    HttpServletRequest r
  ) {
    return CommandResponses.toEntity(
      service.write(
        Long.parseLong(jwt.getSubject()),
        id,
        b,
        k,
        RequestIdFilter.currentRequestId(r)
      ),
      r
    );
  }
}
