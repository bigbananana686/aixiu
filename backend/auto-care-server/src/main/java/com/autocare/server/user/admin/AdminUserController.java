package com.autocare.server.user.admin;

import com.autocare.server.command.CommandResponses;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.ApiResponse;
import com.autocare.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
  private final AdminUserService users;
  private final AdminAccess access;

  public AdminUserController(AdminUserService users, AdminAccess access) {
    this.users = users;
    this.access = access;
  }

  @ModelAttribute
  public void authorize(@AuthenticationPrincipal Jwt jwt) {
    access.require(Long.parseLong(jwt.getSubject()));
  }

  @GetMapping
  public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query,
                                HttpServletRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
      ApiResponse.of(request, users.list(Long.parseLong(jwt.getSubject()), query))
    );
  }

  @PatchMapping("/{userId}")
  public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId, @RequestBody JsonNode body,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  HttpServletRequest request) {
    return CommandResponses.toEntity(users.update(Long.parseLong(jwt.getSubject()), userId, body, key,
      RequestIdFilter.currentRequestId(request)), request);
  }
}
