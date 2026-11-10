package com.autocare.server.availability;

import java.time.LocalDateTime;

/**
 * 一条**仍在占用**的预约（{@code t_service_order} 中 {@code reservation_active = 1} 的行）。
 *
 * <p>只取 {@code occupied_start} 与 {@code effective_end} 两列——可用时段是提示，
 * 它只关心「这段时间被谁占了、占到哪」，订单的其余字段一概不问。
 *
 * <p>两列都读作 UTC，理由见 {@link WindowRow}。按设计 §3.3：区间是半开的
 * {@code [occupied_start, effective_end)}，端点相接不算冲突。
 */
public class OccupiedRow {

    private LocalDateTime occupiedStart;
    private LocalDateTime effectiveEnd;

    public LocalDateTime getOccupiedStart() {
        return occupiedStart;
    }

    public void setOccupiedStart(LocalDateTime occupiedStart) {
        this.occupiedStart = occupiedStart;
    }

    public LocalDateTime getEffectiveEnd() {
        return effectiveEnd;
    }

    public void setEffectiveEnd(LocalDateTime effectiveEnd) {
        this.effectiveEnd = effectiveEnd;
    }
}
