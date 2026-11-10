package com.autocare.server.availability.admin;

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
@RequestMapping("/api/v1/admin/technicians")
public class ScheduleController {

  private final ScheduleService service;
  private final AdminAccess access;

  public ScheduleController(ScheduleService service, AdminAccess access) {
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
    return read(service.list(Long.parseLong(jwt.getSubject()), q), r);
  }

  @GetMapping("/{technicianId}/windows")
  public ResponseEntity<ApiResponse<?>> get(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String technicianId,
    @RequestParam MultiValueMap<String, String> q,
    HttpServletRequest r
  ) {
    return read(
      service.get(Long.parseLong(jwt.getSubject()), technicianId, q),
      r
    );
  }

  @PutMapping("/{technicianId}/windows")
  public ResponseEntity<ApiResponse<JsonNode>> replace(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String technicianId,
    @RequestBody JsonNode body,
    @RequestHeader(value = "Idempotency-Key", required = false) String k,
    HttpServletRequest r
  ) {
    return CommandResponses.toEntity(
      service.replace(
        Long.parseLong(jwt.getSubject()),
        technicianId,
        body,
        k,
        RequestIdFilter.currentRequestId(r)
      ),
      r
    );
  }

  private ResponseEntity<ApiResponse<?>> read(
    Object value,
    HttpServletRequest r
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(new ApiResponse<>(RequestIdFilter.currentRequestId(r), value));
  }
}
