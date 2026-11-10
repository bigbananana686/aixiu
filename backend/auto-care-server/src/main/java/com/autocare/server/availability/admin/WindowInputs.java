package com.autocare.server.availability.admin;

import static com.autocare.server.web.InputValues.*;

import com.autocare.common.error.*;
import com.autocare.server.web.UtcTimes;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.JsonNode;

final class WindowInputs {

  record Range(LocalDate from, LocalDate to) {
    LocalDateTime start() {
      return from
        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
        .withZoneSameInstant(ZoneOffset.UTC)
        .toLocalDateTime();
    }

    LocalDateTime end() {
      return to
        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
        .withZoneSameInstant(ZoneOffset.UTC)
        .toLocalDateTime();
    }
  }

  record Window(LocalDateTime start, LocalDateTime end) {
    Map<String, String> view() {
      return Map.of(
        "windowStart",
        UtcTimes.format(start),
        "windowEnd",
        UtcTimes.format(end)
      );
    }

    boolean covers(LocalDateTime s, LocalDateTime e) {
      return !s.isBefore(start) && !e.isAfter(end);
    }
  }

  record Replacement(Range range, int version, List<Window> windows) {}

  static Range range(String from, String to) {
    try {
      if (
        from == null ||
        to == null ||
        !from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ||
        !to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
      ) throw new DateTimeException("format");
      LocalDate a = LocalDate.parse(from),
        b = LocalDate.parse(to);
      if (
        !a.isBefore(b) || ChronoUnit.DAYS.between(a, b) > 31
      ) throw ApiException.invalid(
        FieldErrors.outOfRange("/rangeEnd", "必须大于起点，跨度不超过31天")
      );
      return new Range(a, b);
    } catch (DateTimeException e) {
      throw ApiException.invalid(
        FieldErrors.invalidFormat("/rangeStart", "必须提供合法日期范围")
      );
    }
  }

  static Replacement replacement(JsonNode b) {
    object(
      b,
      Set.of("rangeStart", "rangeEnd", "expectedScheduleVersion", "windows")
    );
    Range r = range(text(b, "rangeStart"), text(b, "rangeEnd"));
    int v = version(b, "expectedScheduleVersion");
    JsonNode a = required(b, "windows");
    if (!a.isArray()) throw ApiException.invalid(
      FieldErrors.invalidType("/windows", "必须为数组")
    );
    List<Window> windows = new ArrayList<>();
    int index = 0;
    for (JsonNode n : a) {
      String prefix = "/windows/" + index++;
      try {
        object(n, Set.of("windowStart", "windowEnd"));
        LocalDateTime start = time(n, "windowStart"),
          end = time(n, "windowEnd");
        if (
          !start.isBefore(end) ||
          start.isBefore(r.start()) ||
          end.isAfter(r.end())
        ) throw ApiException.invalid(
          FieldErrors.outOfRange(
            "/windowEnd",
            "窗口必须完整位于日期范围内且起点早于终点"
          )
        );
        windows.add(new Window(start, end));
      } catch (ApiException e) {
        ApiException nested = ApiException.of(e.errorCode(), e.getMessage());
        for (FieldError field : e.fieldErrors())
          nested.field(
            new FieldError(
              prefix + field.field(),
              field.code(),
              field.message()
            )
          );
        throw nested;
      }
    }
    windows.sort(
      Comparator.comparing(Window::start).thenComparing(Window::end)
    );
    for (int i = 1; i < windows.size(); i++) if (
      windows
        .get(i)
        .start()
        .isBefore(windows.get(i - 1).end())
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/windows", "窗口不得重叠")
    );
    return new Replacement(r, v, List.copyOf(windows));
  }
}
