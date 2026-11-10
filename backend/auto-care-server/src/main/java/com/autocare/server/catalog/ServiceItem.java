// ServiceItem.java —— 出参 DTO，恰好 7 个字段
package com.autocare.server.catalog;

import java.math.RoundingMode;

public record ServiceItem(
        String serviceItemId, String code, String name,
        int durationMinutes, String unitPrice, String currency, String catalogVersion) {

    public static ServiceItem from(ServiceItemRow row) {
       return new ServiceItem(
               String.valueOf(row.getId()),
               row.getCode(),
               row.getName(),
               row.getDurationMinutes(),
               row.getPrice().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
               row.getCurrency(),
               String.valueOf(row.getVersion())
       );
    }
}