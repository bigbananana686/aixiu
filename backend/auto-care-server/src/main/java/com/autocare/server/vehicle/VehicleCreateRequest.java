package com.autocare.server.vehicle;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * createVehicle 的请求体。
 *
 * <p><b>为什么不是 record</b>：契约 §4.2 的 null 语义栏写着 {@code plateNumber}「不可 null
 * （省略即不提供）」——也就是说「省略」与「显式 null」必须分开。record 走构造器，两者进来都是
 * null，分不出来，于是 {@code {"plateNumber":null}} 会被当成「没给车牌」收下。用 setter 记账即可分开。
 *
 * <p>{@code ownerId} 不在本类里，这是刻意的：归属只来自认证身份（契约 §4.2）。body 里带上它就是
 * 未知字段，由 Jackson 的 fail-on-unknown-properties 报 400 UNKNOWN_FIELD。
 */
public class VehicleCreateRequest {

    private String modelCode;
    private String plateNumber;
    private Integer mileage;

    /** 出现过哪些字段（含显式 null）。 */
    private final Set<String> present = new LinkedHashSet<>();

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        present.add("modelCode");
        this.modelCode = modelCode;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        present.add("plateNumber");
        this.plateNumber = plateNumber;
    }

    public Integer getMileage() {
        return mileage;
    }

    public void setMileage(Integer mileage) {
        present.add("mileage");
        this.mileage = mileage;
    }

    public boolean has(String field) {
        return present.contains(field);
    }
}
