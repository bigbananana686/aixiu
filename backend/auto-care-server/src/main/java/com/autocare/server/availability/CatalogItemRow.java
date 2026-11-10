package com.autocare.server.availability;

/**
 * 目录项的最小投影：本接口只问两件事——这项**存在吗**、**工时多少**。
 *
 * <p>{@code enabled} 也要：停用项不能作为预约意向（v1 的规矩是「停用只影响新意向」），
 * 所以查出来之后还要逐项核对启用位，而不是只在 SQL 里筛。
 */
public class CatalogItemRow {

    private long id;
    private int durationMinutes;
    private Boolean enabled;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
