// ServiceItemListResult.java
package com.autocare.server.catalog;

import java.util.List;

public record ServiceItemListResult(List<ServiceItem> items, int page, int pageSize, long total) {}