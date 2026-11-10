package com.autocare.server.order.quote;

import com.autocare.common.error.*;
import com.autocare.server.order.OrderInputs;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 先做形态/范围校验，再进入幂等；缺省与显式null独立处理。 */
final class QuoteInputs {

  record Item(
    String lineKey,
    String serviceItemId,
    int quantity,
    String catalogVersion
  ) {}

  record Submission(
    String purpose,
    String expectedRowVersion,
    String expectedApprovedQuoteId,
    String expectedPendingQuoteId,
    List<Item> items,
    String reason
  ) {}

  record Decision(String version, String reason) {}

  static Submission submission(JsonNode body) {
    object(
      body,
      "",
      Set.of(
        "purpose",
        "expectedRowVersion",
        "expectedApprovedQuoteId",
        "expectedPendingQuoteId",
        "items",
        "reason"
      )
    );
    String purpose = text(body, "purpose", "");
    if (
      !Set.of("INITIAL", "EXTRA").contains(purpose)
    ) throw ApiException.invalid(
      FieldErrors.notAllowed("/purpose", "必须为INITIAL或EXTRA")
    );
    String version = positive(body, "expectedRowVersion", "", true);
    String approved = nullableId(body, "expectedApprovedQuoteId"),
      pending = nullableId(body, "expectedPendingQuoteId");
    JsonNode nodes = body.get("items");
    if (nodes == null) throw ApiException.invalid(
      FieldErrors.required("/items")
    );
    if (!nodes.isArray()) throw ApiException.invalid(
      FieldErrors.invalidType("/items", "必须为数组")
    );
    if (nodes.size() < 1 || nodes.size() > 50) throw ApiException.invalid(
      FieldErrors.outOfRange("/items", "必须为1—50行")
    );
    List<Item> items = new ArrayList<>();
    Set<String> lines = new HashSet<>();
    int i = 0;
    for (JsonNode n : nodes) {
      String p = "/items/" + i++;
      object(
        n,
        p,
        Set.of("lineKey", "serviceItemId", "quantity", "catalogVersion")
      );
      String line = text(n, "lineKey", p);
      if (
        !line.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
      ) throw ApiException.invalid(
        FieldErrors.invalidFormat(p + "/lineKey", "lineKey格式不合法")
      );
      if (!lines.add(line.toLowerCase(Locale.ROOT))) throw ApiException.invalid(
        FieldErrors.duplicate(
          p + "/lineKey",
          "同版lineKey不可重复（大小写不敏感）"
        )
      );
      String id = positive(n, "serviceItemId", p, false),
        cv = positive(n, "catalogVersion", p, true);
      JsonNode q = n.get("quantity");
      if (q == null) throw ApiException.invalid(
        FieldErrors.required(p + "/quantity")
      );
      if (!q.isIntegralNumber()) throw ApiException.invalid(
        FieldErrors.invalidType(p + "/quantity", "必须为整数")
      );
      if (
        !q.canConvertToInt() || q.intValue() < 1 || q.intValue() > 99
      ) throw ApiException.invalid(
        FieldErrors.outOfRange(p + "/quantity", "必须为1—99")
      );
      items.add(new Item(line, id, q.intValue(), cv));
    }
    return new Submission(
      purpose,
      version,
      approved,
      pending,
      List.copyOf(items),
      body.has("reason") ? reason(body) : null
    );
  }

  static Decision decision(JsonNode body, boolean reject) {
    object(body, "", reject ? Set.of("version", "reason") : Set.of("version"));
    return new Decision(
      positive(body, "version", "", true),
      reject ? reason(body) : null
    );
  }

  static String reason(JsonNode body) {
    return OrderInputs.reason(body.get("reason"), body.has("reason"));
  }

  static String nullableId(JsonNode b, String name) {
    if (!b.has(name)) throw ApiException.invalid(
      FieldErrors.required("/" + name)
    );
    return b.get(name).isNull() ? null : positive(b, name, "", false);
  }

  static String positive(
    JsonNode b,
    String name,
    String prefix,
    boolean version
  ) {
    String s = text(b, name, prefix),
      p = prefix + "/" + name;
    if (
      !s.matches(version ? "[1-9][0-9]{0,9}" : "[1-9][0-9]{0,18}")
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat(p, "必须为无前导零的正整数字符串")
    );
    try {
      long value = Long.parseLong(s);
      if (
        version && value > Integer.MAX_VALUE
      ) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      throw ApiException.invalid(FieldErrors.outOfRange(p, "数值超出范围"));
    }
    return s;
  }

  static long pathId(String value, String field) {
    if (
      value == null || !value.matches("[1-9][0-9]{0,18}")
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/" + field, "ID格式不合法")
    );
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/" + field, "ID超出范围")
      );
    }
  }

  static String text(JsonNode b, String name, String prefix) {
    JsonNode v = b.get(name);
    String p = prefix + "/" + name;
    if (v == null) throw ApiException.invalid(FieldErrors.required(p));
    if (v.isNull()) throw ApiException.invalid(FieldErrors.nullNotAllowed(p));
    if (!v.isString()) throw ApiException.invalid(
      FieldErrors.invalidType(p, "必须为字符串")
    );
    return v.stringValue();
  }

  static void object(JsonNode n, String p, Set<String> allowed) {
    if (n == null || !n.isObject()) throw ApiException.invalid(
      FieldErrors.invalidType(p, "必须为对象")
    );
    for (String name : n.propertyNames())
      if (!allowed.contains(name)) throw ApiException.invalid(
        FieldErrors.unknownField(p + "/" + name, "不接受的字段")
      );
  }
}
