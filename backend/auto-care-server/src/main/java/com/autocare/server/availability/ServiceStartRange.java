package com.autocare.server.availability;

/**
 * 一段可选施工起点的<b>闭区间</b>（契约 §2.3／§4.5）：起点取 {@code earliest} 与 {@code latest}
 * 两端都算可选。时间格式固定 UTC 毫秒（{@code 2026-11-01T01:45:00.000Z}）。
 */
public record ServiceStartRange(String earliest, String latest) {
}
