package com.autocare.server.order.lifecycle;

/**
 * 订单操作留痕的一条（契约 §2.4 {@code OrderOperation}）。创建订单时不产生操作留痕
 * （创建记在状态流水里），所以本片的 {@code operations} 恒为空数组——类型先立住，
 * G1-04 的接单/拒绝开始往里写。
 */
public record OrderOperationEntry(
        String operationId,
        String action,
        String actorId,
        String quoteId,
        String payloadSummary,
        String requestId,
        String createdAt) {
}
