package com.autocare.server.order.quote;

public class QuoteRow {

  private long id;

  public long getId() {
    return id;
  }

  public void setId(long value) {
    id = value;
  }

  private long orderId;

  public long getOrderId() {
    return orderId;
  }

  public void setOrderId(long value) {
    orderId = value;
  }

  private int version;

  public int getVersion() {
    return version;
  }

  public void setVersion(int value) {
    version = value;
  }

  private String purpose;

  public String getPurpose() {
    return purpose;
  }

  public void setPurpose(String value) {
    purpose = value;
  }

  private Long baseApprovedQuoteId;

  public Long getBaseApprovedQuoteId() {
    return baseApprovedQuoteId;
  }

  public void setBaseApprovedQuoteId(Long value) {
    baseApprovedQuoteId = value;
  }

  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    status = value;
  }

  private java.math.BigDecimal totalAmount;

  public java.math.BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(java.math.BigDecimal value) {
    totalAmount = value;
  }

  private String currency;

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String value) {
    currency = value;
  }

  private int totalMinutes;

  public int getTotalMinutes() {
    return totalMinutes;
  }

  public void setTotalMinutes(int value) {
    totalMinutes = value;
  }

  private int addedMinutes;

  public int getAddedMinutes() {
    return addedMinutes;
  }

  public void setAddedMinutes(int value) {
    addedMinutes = value;
  }

  private java.time.LocalDateTime targetEnd;

  public java.time.LocalDateTime getTargetEnd() {
    return targetEnd;
  }

  public void setTargetEnd(java.time.LocalDateTime value) {
    targetEnd = value;
  }

  private java.time.LocalDateTime expiresAt;

  public java.time.LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(java.time.LocalDateTime value) {
    expiresAt = value;
  }

  private long submittedBy;

  public long getSubmittedBy() {
    return submittedBy;
  }

  public void setSubmittedBy(long value) {
    submittedBy = value;
  }

  private java.time.LocalDateTime submittedAt;

  public java.time.LocalDateTime getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(java.time.LocalDateTime value) {
    submittedAt = value;
  }

  private Long confirmedBy;

  public Long getConfirmedBy() {
    return confirmedBy;
  }

  public void setConfirmedBy(Long value) {
    confirmedBy = value;
  }

  private java.time.LocalDateTime confirmedAt;

  public java.time.LocalDateTime getConfirmedAt() {
    return confirmedAt;
  }

  public void setConfirmedAt(java.time.LocalDateTime value) {
    confirmedAt = value;
  }

  private String terminalReason;

  public String getTerminalReason() {
    return terminalReason;
  }

  public void setTerminalReason(String value) {
    terminalReason = value;
  }

  private String contentHash;

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String value) {
    contentHash = value;
  }
}
