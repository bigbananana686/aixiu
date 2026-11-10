package com.autocare.server.web;

import com.autocare.common.error.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 严格JSON值校验；返回已规范化的值，用于命令摘要和业务处理。 */
public final class InputValues {

  private InputValues() {}

  public static void object(JsonNode b, Set<String> fields) {
    if (b == null || !b.isObject()) throw ApiException.invalid(
      FieldErrors.invalidType("/", "必须为对象")
    );
    for (String f : b.propertyNames())
      if (!fields.contains(f)) throw ApiException.invalid(
        FieldErrors.unknownField("/" + f, "不接受的字段")
      );
  }

  public static JsonNode required(JsonNode b, String n) {
    JsonNode v = b.get(n);
    if (v == null) throw ApiException.invalid(FieldErrors.required("/" + n));
    if (v.isNull()) throw ApiException.invalid(
      FieldErrors.nullNotAllowed("/" + n)
    );
    return v;
  }

  public static String text(JsonNode b, String n) {
    JsonNode v = required(b, n);
    if (!v.isString()) throw ApiException.invalid(
      FieldErrors.invalidType("/" + n, "必须为字符串")
    );
    return v.stringValue();
  }

  public static String boundedText(JsonNode b, String n, int max) {
    String s = text(b, n);
    if (
      s.codePoints().anyMatch(Character::isISOControl)
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/" + n, "不得含控制字符")
    );
    s = s.strip();
    if (
      s.isEmpty() || s.codePointCount(0, s.length()) > max
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/" + n, "长度必须为1—" + max)
    );
    return s;
  }

  public static long id(String value, String field) {
    if (
      value == null || !value.matches("[1-9][0-9]{0,18}")
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/" + field, "必须为正整数字符串")
    );
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/" + field, "ID超出范围")
      );
    }
  }

  public static int version(JsonNode b, String n) {
    String s = text(b, n);
    if (!s.matches("[1-9][0-9]{0,9}")) throw ApiException.invalid(
      FieldErrors.invalidFormat("/" + n, "必须为无前导零的正整数字符串")
    );
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/" + n, "版本超出范围")
      );
    }
  }

  public static int integer(JsonNode b, String n, int min, int max) {
    JsonNode v = required(b, n);
    if (!v.isIntegralNumber()) throw ApiException.invalid(
      FieldErrors.invalidType("/" + n, "必须为整数")
    );
    if (
      !v.canConvertToInt() || v.intValue() < min || v.intValue() > max
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/" + n, "必须为" + min + "—" + max)
    );
    return v.intValue();
  }

  public static boolean bool(JsonNode b, String n) {
    JsonNode v = required(b, n);
    if (!v.isBoolean()) throw ApiException.invalid(
      FieldErrors.invalidType("/" + n, "必须为布尔值")
    );
    return v.booleanValue();
  }

  public static String money(JsonNode b, String n) {
    String s = text(b, n);
    if (
      !s.matches("(0|[1-9][0-9]{0,4})\\.[0-9]{2}")
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/" + n, "必须为0.00—99999.99的两位小数字符串")
    );
    return s;
  }

  public static LocalDateTime time(JsonNode b, String n) {
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
}
