package com.autocare.server.order.query;

/** 列表隐私投影：没有地址、车辆快照、原因或内部原始输入。 */
public record OrderSummary(String orderId, String orderNo, String state, String rowVersion,
        String ownerId, String vehicleId, String technicianId, String serviceStart,
        String committedEnd, String effectiveEnd, String approvedQuoteId, String pendingQuoteId,
        String acceptDeadline, String createdAt, String updatedAt) {}
