package com.autocare.server.web;

import com.autocare.common.error.*;
import java.util.*;
import org.springframework.util.MultiValueMap;

public record ListInputs(int page, int pageSize, Boolean enabled) {
  public static ListInputs parse(MultiValueMap<String, String> q) {
    for (var e : q.entrySet())
      if (
        !Set.of("page", "pageSize", "enabled").contains(e.getKey()) ||
        e.getValue().size() != 1
      ) throw ApiException.invalid(
        FieldErrors.notAllowed("/" + e.getKey(), "未知或重复参数")
      );
    Boolean enabled = null;
    if (q.containsKey("enabled")) {
      String v = q.getFirst("enabled");
      if (!"true".equals(v) && !"false".equals(v)) throw ApiException.invalid(
        FieldErrors.invalidFormat("/enabled", "必须为true或false")
      );
      enabled = Boolean.valueOf(v);
    }
    return new ListInputs(
      number(q, "page", 1, 10000),
      number(q, "pageSize", 20, 100),
      enabled
    );
  }

  private static int number(
    MultiValueMap<String, String> q,
    String n,
    int fallback,
    int max
  ) {
    if (!q.containsKey(n)) return fallback;
    try {
      String s = q.getFirst(n);
      if (
        s == null || !s.matches("[1-9][0-9]*")
      ) throw new NumberFormatException();
      int v = Integer.parseInt(s);
      if (v > max) throw new NumberFormatException();
      return v;
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/" + n, "分页超出范围")
      );
    }
  }

  public int offset() {
    return (page - 1) * pageSize;
  }
}
