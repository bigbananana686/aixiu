package com.autocare.server.vehicle;

import java.time.LocalDateTime;

public class VehicleEntity {
    private Long id;
    private Long ownerId;
    private String modelCode;
    private String plateMaskSource;
    private String plateMasked;
    private Integer mileage;
    private Integer rowVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public VehicleEntity(Long id, Long ownerId, String modelCode, String plateMaskSource, String plateMasked, Integer mileage, Integer rowVersion, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.modelCode = modelCode;
        this.plateMaskSource = plateMaskSource;
        this.plateMasked = plateMasked;
        this.mileage = mileage;
        this.rowVersion = rowVersion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public VehicleEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public String getPlateMaskSource() {
        return plateMaskSource;
    }

    public void setPlateMaskSource(String plateMaskSource) {
        this.plateMaskSource = plateMaskSource;
    }

    public Integer getMileage() {
        return mileage;
    }

    public void setMileage(Integer mileage) {
        this.mileage = mileage;
    }

    public String getPlateMasked() {
        return plateMasked;
    }

    public void setPlateMasked(String plateMasked) {
        this.plateMasked = plateMasked;
    }

    public Integer getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Integer rowVersion) {
        this.rowVersion = rowVersion;
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
