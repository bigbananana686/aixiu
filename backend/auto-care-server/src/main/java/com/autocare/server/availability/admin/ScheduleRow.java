package com.autocare.server.availability.admin;

public class ScheduleRow {

  private long technicianId;

  public long getTechnicianId() {
    return technicianId;
  }

  public void setTechnicianId(long v) {
    technicianId = v;
  }

  private String displayName;

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String v) {
    displayName = v;
  }

  private boolean enabled;

  public boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  private int scheduleVersion;

  public int getScheduleVersion() {
    return scheduleVersion;
  }

  public void setScheduleVersion(int v) {
    scheduleVersion = v;
  }
}
