package com.autocare.server.order.lifecycle;

/**
 * 订单状态流水的一条（契约 §2.4 {@code OrderStateLogEntry}）。只追加，不更新；
 * 创建订单时写第一条：{@code fromState = null}（创建时没有前态）。
 */
public record OrderStateLogEntry(
        String fromState,
        String toState,
        String action,
        String actorId,
        String requestId,
        String createdAt) {
}
