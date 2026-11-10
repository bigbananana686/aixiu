package com.autocare.server.order.exception;

import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderExceptionController {

  private final OrderExceptionService service;
  private final AdminAccess access;

  public OrderExceptionController(
    OrderExceptionService service,
    AdminAccess access
  ) {
    this.service = service;
    this.access = access;
  }

  @ModelAttribute
  public void authorize(@AuthenticationPrincipal Jwt jwt) {
    access.require(Long.parseLong(jwt.getSubject()));
  }

  @GetMapping("/api/v1/admin/order-exceptions")
  public ApiResponse<Map<String, Object>> list(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam MultiValueMap<String, String> q,
    HttpServletRequest r
  ) {
    return ApiResponse.of(r, service.list(Long.parseLong(jwt.getSubject()), q));
  }
}
