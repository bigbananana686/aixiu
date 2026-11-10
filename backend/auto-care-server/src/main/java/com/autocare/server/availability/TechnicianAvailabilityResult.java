package com.autocare.server.availability;

import java.util.List;

/**
 * {@code getTechnicianAvailability} 的响应体（契约 §2.3／§4.5）。
 *
 * <p>{@code date} 原样回显请求里的日期字符串；{@code timeZone} 固定 {@code Asia/Shanghai}；
 * {@code totalMinutes} 是所选目录项工时之和（本接口不带数量）。
 */
public record TechnicianAvailabilityResult(
        String date,
        String timeZone,
        int totalMinutes,
        List<TechnicianAvailability> technicians) {
}
