package com.autocare.server.order.fulfillment;

import com.autocare.common.error.*;
import com.autocare.server.order.OrderInputs;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

final class FulfillmentInputs {

  record Completion(
    int version,
    LocalDateTime finish,
    Integer mileage,
    String result
  ) {}

  static void object(JsonNode b, Set<String> allowed) {
    if (b == null || !b.isObject()) throw ApiException.invalid(
      FieldErrors.invalidType("", "必须为对象")
    );
    for (String n : b.propertyNames())
      if (!allowed.contains(n)) throw ApiException.invalid(
        FieldErrors.unknownField("/" + n, "不接受的字段")
      );
  }

  static int version(JsonNode b) {
    return OrderInputs.version(
      b.get("expectedRowVersion"),
      b.has("expectedRowVersion")
    );
  }

  static String text(JsonNode b, String n) {
    JsonNode v = b.get(n);
    String p = "/" + n;
    if (v == null) throw ApiException.invalid(FieldErrors.required(p));
    if (v.isNull()) throw ApiException.invalid(FieldErrors.nullNotAllowed(p));
    if (!v.isString()) throw ApiException.invalid(
      FieldErrors.invalidType(p, "必须为字符串")
    );
    return v.stringValue();
  }

  static LocalDateTime time(JsonNode b, String n) {
    String s = text(b, n);
    if (
      !s.matches(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?(Z|[+-][0-9]{2}:[0-9]{2})"
      )
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat(
        "/" + n,
        "必须为带偏移的RFC3339时间，小数最多3位"
      )
    );
    try {
      return OffsetDateTime.parse(s)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .toLocalDateTime();
    } catch (DateTimeException e) {
      throw ApiException.invalid(
        FieldErrors.invalidFormat("/" + n, "时间不合法")
      );
    }
  }

  static Completion completion(JsonNode b) {
    object(
      b,
      Set.of("expectedRowVersion", "actualFinish", "mileage", "serviceResult")
    );
    int v = version(b);
    LocalDateTime finish = time(b, "actualFinish");
    String result = text(b, "serviceResult");
    if (
      result.codePoints().anyMatch(Character::isISOControl)
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/serviceResult", "不得含控制字符")
    );
    result = result.strip();
    if (
      result.isEmpty() || result.codePointCount(0, result.length()) > 2000
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/serviceResult", "必须为1—2000字符")
    );
    Integer mileage = null;
    if (b.has("mileage")) {
      JsonNode m = b.get("mileage");
      if (m.isNull()) throw ApiException.invalid(
        FieldErrors.nullNotAllowed("/mileage")
      );
      if (!m.isIntegralNumber()) throw ApiException.invalid(
        FieldErrors.invalidType("/mileage", "必须为整数")
      );
      if (
        !m.canConvertToInt() || m.intValue() < 0 || m.intValue() > 9999999
      ) throw ApiException.invalid(
        FieldErrors.outOfRange("/mileage", "必须为0—9999999")
      );
      mileage = m.intValue();
    }
    return new Completion(v, finish, mileage, result);
  }
}
