package com.autocare.server.knowledge;

import com.autocare.common.error.*;
import java.util.*;
import org.springframework.util.MultiValueMap;

public final class KnowledgeInputs {

  private KnowledgeInputs() {}

  public static String text(
    String value,
    String field,
    int max,
    boolean required
  ) {
    if (value == null) {
      if (required) throw ApiException.invalid(
        FieldErrors.required("/" + field)
      );
      return null;
    }
    value = value.strip();
    if (
      value.isEmpty() || value.codePointCount(0, value.length()) > max
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/" + field, "字段长度超出范围")
    );
    return value;
  }

  public static String key(String value) {
    if (
      value != null && !value.matches("[a-z0-9][a-z0-9-]{2,63}")
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/documentKey", "资料标识不合法")
    );
    return value;
  }

  public static String tag(String value) {
    if (
      value != null && !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/modelTags", "型号标识不合法")
    );
    return value;
  }

  public static List<String> tags(String value) {
    if (value == null) return List.of("GENERIC");
    var tags = Arrays.stream(value.split(",", -1)).map(String::strip).toList();
    if (
      tags.isEmpty() ||
      tags.size() > 20 ||
      new HashSet<>(tags).size() != tags.size()
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/modelTags", "型号数量须为1至20且不重复")
    );
    tags.forEach(KnowledgeInputs::tag);
    return tags.stream().sorted().toList();
  }

  public static void query(
    MultiValueMap<String, String> query,
    Set<String> allowed
  ) {
    for (var e : query.entrySet()) {
      if (!allowed.contains(e.getKey())) throw ApiException.invalid(
        FieldErrors.unknownField("/" + e.getKey(), "未知参数")
      );
      if (e.getValue().size() != 1) throw ApiException.invalid(
        FieldErrors.duplicate("/" + e.getKey(), "参数不可重复")
      );
    }
  }

  public static int page(String value, String field, int fallback, int max) {
    if (value == null) return fallback;
    try {
      if (!value.matches("[1-9][0-9]{0,8}")) throw new NumberFormatException();
      int n = Integer.parseInt(value);
      if (n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/" + field, "分页越界")
      );
    }
  }

  public static long id(String value, String field) {
    try {
      if (
        value == null || !value.matches("[1-9][0-9]{0,18}")
      ) throw new NumberFormatException();
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.invalidFormat("/" + field, "ID格式不合法")
      );
    }
  }
}
