package com.autocare.server.order;

/**
 * 报价条目快照（契约 §2.4 {@code BookingItemSnapshot}）。一经写入不再变化——
 * 目录后来改价、停用都不影响它，订单可执行范围只认快照。
 */
public record BookingItemSnapshot(
        String serviceItemId,
        String code,
        String name,
        String unitPrice,
        String currency,
        int quantity,
        int durationMinutes,
        String catalogVersion) {
}
