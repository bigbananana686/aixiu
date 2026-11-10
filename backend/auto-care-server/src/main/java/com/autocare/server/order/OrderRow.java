package com.autocare.server.order;

import java.time.LocalDateTime;

/** 数据库当前订单投影；不直接向 HTTP 暴露原始列。 */
public class OrderRow {
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
    private boolean reservationActive;
    private Long approvedQuoteId;
    private Long pendingQuoteId;
    private LocalDateTime acceptDeadline;
    private String bookingItemsJson;
    private String rulesSnapshotJson;
    private String addressSnapshot;
    private int mileageSnapshot;
    private String modelSnapshot;
    private String plateSnapshot;
    private LocalDateTime actualStart;
    private LocalDateTime actualFinish;
    private String serviceResult;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public long getId() { return id; }
    public void setId(long value) { id = value; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String value) { orderNo = value; }
    public long getOwnerId() { return ownerId; }
    public void setOwnerId(long value) { ownerId = value; }
    public long getVehicleId() { return vehicleId; }
    public void setVehicleId(long value) { vehicleId = value; }
    public long getTechnicianId() { return technicianId; }
    public void setTechnicianId(long value) { technicianId = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public int getRowVersion() { return rowVersion; }
    public void setRowVersion(int value) { rowVersion = value; }
    public LocalDateTime getServiceStart() { return serviceStart; }
    public void setServiceStart(LocalDateTime value) { serviceStart = value; }
    public LocalDateTime getOccupiedStart() { return occupiedStart; }
    public void setOccupiedStart(LocalDateTime value) { occupiedStart = value; }
    public LocalDateTime getCommittedEnd() { return committedEnd; }
    public void setCommittedEnd(LocalDateTime value) { committedEnd = value; }
    public LocalDateTime getEffectiveEnd() { return effectiveEnd; }
    public void setEffectiveEnd(LocalDateTime value) { effectiveEnd = value; }
    public boolean getReservationActive() { return reservationActive; }
    public void setReservationActive(boolean value) { reservationActive = value; }
    public Long getApprovedQuoteId() { return approvedQuoteId; }
    public void setApprovedQuoteId(Long value) { approvedQuoteId = value; }
    public Long getPendingQuoteId() { return pendingQuoteId; }
    public void setPendingQuoteId(Long value) { pendingQuoteId = value; }
    public LocalDateTime getAcceptDeadline() { return acceptDeadline; }
    public void setAcceptDeadline(LocalDateTime value) { acceptDeadline = value; }
    public String getBookingItemsJson() { return bookingItemsJson; }
    public void setBookingItemsJson(String value) { bookingItemsJson = value; }
    public String getRulesSnapshotJson() { return rulesSnapshotJson; }
    public void setRulesSnapshotJson(String value) { rulesSnapshotJson = value; }
    public String getAddressSnapshot() { return addressSnapshot; }
    public void setAddressSnapshot(String value) { addressSnapshot = value; }
    public int getMileageSnapshot() { return mileageSnapshot; }
    public void setMileageSnapshot(int value) { mileageSnapshot = value; }
    public String getModelSnapshot() { return modelSnapshot; }
    public void setModelSnapshot(String value) { modelSnapshot = value; }
    public String getPlateSnapshot() { return plateSnapshot; }
    public void setPlateSnapshot(String value) { plateSnapshot = value; }
    public LocalDateTime getActualStart() { return actualStart; }
    public void setActualStart(LocalDateTime value) { actualStart = value; }
    public LocalDateTime getActualFinish() { return actualFinish; }
    public void setActualFinish(LocalDateTime value) { actualFinish = value; }
    public String getServiceResult() { return serviceResult; }
    public void setServiceResult(String value) { serviceResult = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
