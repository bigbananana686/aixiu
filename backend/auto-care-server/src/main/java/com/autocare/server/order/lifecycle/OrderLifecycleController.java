package com.autocare.server.order.lifecycle;

import com.autocare.server.order.query.OrderDetail;
import com.autocare.server.order.query.OrderListResult;
import com.autocare.server.order.query.OrderQueryService;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.identity.Role;
import com.autocare.server.command.CommandResponses;
import com.autocare.server.web.ApiResponse;
import com.autocare.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderLifecycleController {
    private final OrderLifecycleService service;
    private final OrderQueryService queries;

    public OrderLifecycleController(OrderLifecycleService service, OrderQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    /** MVC 在绑定 @RequestBody 前执行：错误角色不被未知字段/畸形 JSON 抢先改报 400。 */
    @ModelAttribute
    public void authorizeBeforeBody(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        Role required = null;
        if ("POST".equals(request.getMethod())) {
            required = request.getRequestURI().endsWith("/cancel") ? Role.OWNER : Role.TECH;
        }
        service.currentRole(actor(jwt), required);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<OrderListResult>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new ApiResponse<>(RequestIdFilter.currentRequestId(request), queries.list(actor(jwt), query)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderDetail>> detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderId, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new ApiResponse<>(RequestIdFilter.currentRequestId(request), queries.detail(actor(jwt), orderId)));
    }

    @PostMapping("/{orderId}/accept")
    public ResponseEntity<ApiResponse<JsonNode>> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
            @RequestBody OrderVersionRequest body, @RequestHeader(value="Idempotency-Key", required=false) String key,
            HttpServletRequest request) {
        return command(jwt, orderId, OrderAction.ACCEPT, body, key, request);
    }

    @PostMapping("/{orderId}/decline")
    public ResponseEntity<ApiResponse<JsonNode>> decline(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
            @RequestBody DeclineOrderRequest body, @RequestHeader(value="Idempotency-Key", required=false) String key,
            HttpServletRequest request) {
        return command(jwt, orderId, OrderAction.DECLINE, body, key, request);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<JsonNode>> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
            @RequestBody OrderVersionRequest body, @RequestHeader(value="Idempotency-Key", required=false) String key,
            HttpServletRequest request) {
        return command(jwt, orderId, OrderAction.CANCEL, body, key, request);
    }

    @PostMapping("/{orderId}/depart")
    public ResponseEntity<ApiResponse<JsonNode>> depart(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
            @RequestBody OrderVersionRequest body, @RequestHeader(value="Idempotency-Key", required=false) String key,
            HttpServletRequest request) {
        return command(jwt, orderId, OrderAction.DEPART, body, key, request);
    }

    @PostMapping("/{orderId}/arrive")
    public ResponseEntity<ApiResponse<JsonNode>> arrive(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
            @RequestBody OrderVersionRequest body, @RequestHeader(value="Idempotency-Key", required=false) String key,
            HttpServletRequest request) {
        return command(jwt, orderId, OrderAction.ARRIVE, body, key, request);
    }

    private ResponseEntity<ApiResponse<JsonNode>> command(Jwt jwt, String orderId, OrderAction action,
            OrderVersionRequest body, String key, HttpServletRequest request) {
        return CommandResponses.toEntity(service.execute(actor(jwt), orderId, action, body, key,
                RequestIdFilter.currentRequestId(request)), request);
    }

    private long actor(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException e) { throw ApiException.of(ErrorCode.UNAUTHENTICATED); }
    }
}
