package com.autocare.ai.booking;

import java.util.List;

/** 模型只能提取建议值；不含用户身份、授权或订单操作。 */
public record BookingDraft(
  VehicleHints vehicleHints,
  List<String> symptoms,
  String preferredTimeText,
  TimeRange preferredTimeRange,
  List<String> missingFields,
  List<EvidenceSpan> evidenceSpans,
  List<String> limitations
) {
  public record VehicleHints(
    String modelCode,
    Integer mileage,
    String plateHint
  ) {}

  public record TimeRange(String earliest, String latest) {}

  /** Unicode 字符偏移，半开区间。 */
  public record EvidenceSpan(String field, int start, int end, String text) {}
}
