package com.autocare.server.order.booking;

import java.time.LocalDateTime;

/**
 * {@code t_service_order} 的插入行。字段名与列名一一对应（MyBatis #{...} 直取）。
 *
 * <p>pending/approved 创建时为 NULL；ai_parse 仅关联当前车主的成功草稿调用；
 * {@code quote_seq} 默认 0，第一版初始报价发布时才 +1。
 *
 * <p>{@code orderNo} 先写占位唯一值（去横线 UUID，恰 32 字符）、插入后按「AC + UTC 日期 + 三位序号(取主键)」
 * 回填——主键唯一所以单号必然唯一，不存在并发撞号（契约样例 {@code AC20261024001} 同形）。
 */
public class OrderInsertRow {

  private long id;
  private String orderNo;
  private long ownerId;
  private long vehicleId;
  private long technicianId;
  private String state;
  private int rowVersion;
  private LocalDateTime serviceStart;
  private LocalDateTime occupiedStart;
  private LocalDateTime committedEnd;
  private LocalDateTime effectiveEnd;
  private LocalDateTime acceptDeadline;
  private String bookingItemsJson;
  private String rulesSnapshotJson;
  private String addressSnapshot;
  private String rawInput;
  private Long aiParseId;

  public Long getAiParseId() {
    return aiParseId;
  }

  public void setAiParseId(Long aiParseId) {
    this.aiParseId = aiParseId;
  }

  private int mileageSnapshot;
  private String modelSnapshot;
  private String plateSnapshot;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getOrderNo() {
    return orderNo;
  }

  public void setOrderNo(String orderNo) {
    this.orderNo = orderNo;
  }

  public long getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(long ownerId) {
    this.ownerId = ownerId;
  }

  public long getVehicleId() {
    return vehicleId;
  }

  public void setVehicleId(long vehicleId) {
    this.vehicleId = vehicleId;
  }

  public long getTechnicianId() {
    return technicianId;
  }

  public void setTechnicianId(long technicianId) {
    this.technicianId = technicianId;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public int getRowVersion() {
    return rowVersion;
  }

  public void setRowVersion(int rowVersion) {
    this.rowVersion = rowVersion;
  }

  public LocalDateTime getServiceStart() {
    return serviceStart;
  }

  public void setServiceStart(LocalDateTime serviceStart) {
    this.serviceStart = serviceStart;
  }

  public LocalDateTime getOccupiedStart() {
    return occupiedStart;
  }

  public void setOccupiedStart(LocalDateTime occupiedStart) {
    this.occupiedStart = occupiedStart;
  }

  public LocalDateTime getCommittedEnd() {
    return committedEnd;
  }

  public void setCommittedEnd(LocalDateTime committedEnd) {
    this.committedEnd = committedEnd;
  }

  public LocalDateTime getEffectiveEnd() {
    return effectiveEnd;
  }

  public void setEffectiveEnd(LocalDateTime effectiveEnd) {
    this.effectiveEnd = effectiveEnd;
  }

  public LocalDateTime getAcceptDeadline() {
    return acceptDeadline;
  }

  public void setAcceptDeadline(LocalDateTime acceptDeadline) {
    this.acceptDeadline = acceptDeadline;
  }

  public String getBookingItemsJson() {
    return bookingItemsJson;
  }

  public void setBookingItemsJson(String bookingItemsJson) {
    this.bookingItemsJson = bookingItemsJson;
  }

  public String getRulesSnapshotJson() {
    return rulesSnapshotJson;
  }

  public void setRulesSnapshotJson(String rulesSnapshotJson) {
    this.rulesSnapshotJson = rulesSnapshotJson;
  }

  public String getAddressSnapshot() {
    return addressSnapshot;
  }

  public void setAddressSnapshot(String addressSnapshot) {
    this.addressSnapshot = addressSnapshot;
  }

  /** 用户原始描述（doc12：不回显，只存证）。 */
  public String getRawInput() {
    return rawInput;
  }

  public void setRawInput(String rawInput) {
    this.rawInput = rawInput;
  }

  public int getMileageSnapshot() {
    return mileageSnapshot;
  }

  public void setMileageSnapshot(int mileageSnapshot) {
    this.mileageSnapshot = mileageSnapshot;
  }

  public String getModelSnapshot() {
    return modelSnapshot;
  }

  public void setModelSnapshot(String modelSnapshot) {
    this.modelSnapshot = modelSnapshot;
  }

  public String getPlateSnapshot() {
    return plateSnapshot;
  }

  public void setPlateSnapshot(String plateSnapshot) {
    this.plateSnapshot = plateSnapshot;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
