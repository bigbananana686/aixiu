package com.autocare.server.order.lifecycle;

public record AcceptOrderResult(String orderId, String state, String rowVersion,
        String acceptDeadline, String committedEnd, String effectiveEnd, String acceptedAt) {}
