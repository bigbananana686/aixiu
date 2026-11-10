package com.autocare.server.order.booking;

import java.math.BigDecimal;

/**
 * 目录项的报价投影：锁内 {@code FOR SHARE} 稳定读取的结果——快照的价格、名称、工时、版本全从这一行来。
 *
 * <p>行的读取顺序是 {@code serviceItemId} 升序（契约 §5.1 事务内顺序明文要求），
 * 快照数组的顺序就按它固定。
 */
public class CatalogQuoteRow {

    private long id;
    private String code;
    private String name;
    private BigDecimal price;
    private String currency;
    private int durationMinutes;
    private Integer version;
    private Boolean enabled;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
