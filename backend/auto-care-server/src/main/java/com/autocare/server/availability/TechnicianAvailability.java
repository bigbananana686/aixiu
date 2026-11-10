package com.autocare.server.availability;

import java.util.List;

/**
 * 一个技师在查询日的可选起点集合。
 *
 * <p>只暴露 {@code technicianId} 与 {@code displayName} 两项元信息——其他预约、车主、
 * 联系方式一律不出这个接口（契约 §4.5 的对象归属栏）。
 */
public record TechnicianAvailability(
        String technicianId,
        String displayName,
        List<ServiceStartRange> serviceStartRanges) {
}
