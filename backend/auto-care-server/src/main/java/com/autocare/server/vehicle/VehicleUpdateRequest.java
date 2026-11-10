package com.autocare.server.vehicle;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * updateVehicle 的请求体。
 *
 * <p><b>为什么用 setter 而不是 record</b>：契约要求区分「省略」与「显式 null」——省略＝不修改，
 * 显式 null＝400 NULL_NOT_ALLOWED。record 走构造器，两种情况进来都是 null，分不出来。
 *
 * <p><b>expectedRowVersion 的 setter 收 {@link JsonNode} 而不是 String</b>：Jackson 默认会把 JSON
 * 数值强制转成 String，那样 {@code {"expectedRowVersion":1}} 会被悄悄当成 {@code "1"} 收下。
 * 契约 §2.1 明文「0、数字3、"03" 拒绝」，§1.5 的 {@code INVALID_TYPE} 就是「ID/版本写成数值」。
 * 所以这里先看节点类型：是数值就记进 {@link #wrongType}，由 service 报 400 INVALID_TYPE。
 */
public class VehicleUpdateRequest {

    private String expectedRowVersion;
    private String modelCode;
    private String plateNumber;
    private Integer mileage;

    /** 出现过哪些字段（含显式 null）。 */
    private final Set<String> present = new LinkedHashSet<>();

    /** 哪些字段的 JSON 类型不对。 */
    private final Set<String> wrongType = new LinkedHashSet<>();

    public String getExpectedRowVersion() {
        return expectedRowVersion;
    }

    public void setExpectedRowVersion(JsonNode node) {
        present.add("expectedRowVersion");
        if (node == null || node.isNull()) {
            expectedRowVersion = null;
            return;
        }
        if (!node.isString()) {
            wrongType.add("expectedRowVersion");
            expectedRowVersion = null;
            return;
        }
        expectedRowVersion = node.stringValue();
    }

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

    public boolean hasWrongType(String field) {
        return wrongType.contains(field);
    }
}
