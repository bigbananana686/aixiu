package com.autocare.server.catalog;

import java.math.BigDecimal;

public class ServiceItemRow {
    private Long id;
    private String code;
    private String name;
    private Integer durationMinutes;
    private BigDecimal price;
    private String currency;
    private Integer version;

    public ServiceItemRow() {}
    public ServiceItemRow(Long id, String code, String name, Integer durationMinutes,
                          BigDecimal price, String currency, Integer version) {
        this.id = id; this.code = code; this.name = name;
        this.durationMinutes = durationMinutes; this.price = price;
        this.currency = currency; this.version = version;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
