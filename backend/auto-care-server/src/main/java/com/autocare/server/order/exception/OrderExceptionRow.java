package com.autocare.server.order.exception;

import com.autocare.server.web.UtcTimes;
import java.time.LocalDateTime;
import java.util.*;

public record OrderExceptionRow(
  long id,
  String type,
  long orderId,
  String requestId,
  LocalDateTime requestedTargetEnd,
  LocalDateTime committedEnd,
  LocalDateTime actualFinish,
  Integer overPlanMinutes,
  LocalDateTime recordedAt,
  boolean resolved
) {
  public Map<String, Object> view() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("exceptionId", "" + id);
    m.put("type", type);
    m.put("orderId", "" + orderId);
    m.put("requestId", requestId);
    if ("DEFERRAL_SLOT_CONFLICT".equals(type)) m.put(
      "requestedTargetEnd",
      UtcTimes.format(requestedTargetEnd)
    );
    else {
      m.put("committedEnd", UtcTimes.format(committedEnd));
      m.put("actualFinish", UtcTimes.format(actualFinish));
      m.put("overPlanMinutes", overPlanMinutes);
    }
    m.put("recordedAt", UtcTimes.format(recordedAt));
    m.put("resolved", resolved);
    return m;
  }
}
