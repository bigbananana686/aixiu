package com.autocare.server.order.lifecycle;

public record OrderStateResult(String orderId, String state, String rowVersion,
        String committedEnd, String effectiveEnd, String approvedQuoteId, String pendingQuoteId,
        String actualStart, String actualFinish, boolean reservationActive, String changedAt) {}
