package com.autocare.server.order.booking;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.server.command.CommandResponses;
import com.autocare.server.web.ApiResponse;
import com.autocare.server.web.RequestIdFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 创建预约。只做搬运：取身份、取键、把请求体交给 service——
 * 「现在能不能约」的全部判断都在 service 与锁内（契约 §5.1 的事务内顺序）。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JsonNode>> create(
            @RequestBody CreateOrderRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        long actorId;
        try {
            actorId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return CommandResponses.toEntity(
                orderService.create(actorId, body, key, RequestIdFilter.currentRequestId(request)), request);
    }
}
