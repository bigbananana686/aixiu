package com.autocare.server.availability;

/**
 * 技师资源行的查询投影——可用时段计算只需要三列：身份、显示名、通勤分钟。
 *
 * <p><b>为什么是普通类而不是 record</b>：MyBatis 走 setter 注值，record 没有 setter，
 * 查出来会是一行全 null 的对象还不报错——错得最安静的那种。仓库里 {@code ServiceItemRow}
 * 已经为同一个理由用过普通类，这里保持一致。
 */
public class TechnicianRow {

    private long technicianId;
    private String displayName;
    private int commuteMinutes;

    public long getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(long technicianId) {
        this.technicianId = technicianId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** 设计 §3.1：默认固定通勤 30 分钟，种子也按这个值入的。 */
    public int getCommuteMinutes() {
        return commuteMinutes;
    }

    public void setCommuteMinutes(int commuteMinutes) {
        this.commuteMinutes = commuteMinutes;
    }
}
