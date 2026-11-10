package com.autocare.server.order.query;

import java.util.List;

public record OrderListResult(List<OrderSummary> items, int page, int pageSize, long total) {}
