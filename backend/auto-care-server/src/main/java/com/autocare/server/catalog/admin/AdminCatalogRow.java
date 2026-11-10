package com.autocare.server.catalog.admin;

public class AdminCatalogRow {

  private Long id;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    id = value;
  }

  private String code;

  public String getCode() {
    return code;
  }

  public void setCode(String value) {
    code = value;
  }

  private String name;

  public String getName() {
    return name;
  }

  public void setName(String value) {
    name = value;
  }

  private int durationMinutes;

  public int getDurationMinutes() {
    return durationMinutes;
  }

  public void setDurationMinutes(int value) {
    durationMinutes = value;
  }

  private java.math.BigDecimal price;

  public java.math.BigDecimal getPrice() {
    return price;
  }

  public void setPrice(java.math.BigDecimal value) {
    price = value;
  }

  private String currency;

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String value) {
    currency = value;
  }

  private boolean enabled;

  public boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  private int version;

  public int getVersion() {
    return version;
  }

  public void setVersion(int value) {
    version = value;
  }

  private java.time.LocalDateTime createdAt;

  public java.time.LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(java.time.LocalDateTime value) {
    createdAt = value;
  }

  private java.time.LocalDateTime updatedAt;

  public java.time.LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(java.time.LocalDateTime value) {
    updatedAt = value;
  }
}
