package com.autocare.ai.booking;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** 结构、数值、原文证据与时间边界二次验证。 */
public final class DraftValidator {

  private DraftValidator() {}

  public static BookingDraft validate(
    BookingDraft d,
    String text,
    Instant now
  ) {
    check(d != null && d.vehicleHints() != null);
    strings(d.symptoms(), 20, 200);
    strings(d.missingFields(), 20, 64);
    strings(d.limitations(), 10, 200);
    var h = d.vehicleHints();
    optional(h.modelCode(), 64);
    optional(h.plateHint(), 16);
    optional(d.preferredTimeText(), 200);
    check(h.mileage() == null || (h.mileage() >= 0 && h.mileage() <= 9999999));
    check(d.evidenceSpans() != null && d.evidenceSpans().size() <= 50);
    int length = text.codePointCount(0, text.length());
    Map<String, BookingDraft.EvidenceSpan> spans = new HashMap<>();
    for (var e : d.evidenceSpans()) {
      check(
        e != null &&
          e.field() != null &&
          e.field().length() <= 64 &&
          e.text() != null &&
          !e.text().isBlank()
      );
      check(e.start() >= 0 && e.end() > e.start() && e.end() <= length);
      String raw = text.substring(
        text.offsetByCodePoints(0, e.start()),
        text.offsetByCodePoints(0, e.end())
      );
      check(raw.equals(e.text()) && spans.putIfAbsent(e.field(), e) == null);
      check(
        Set.of(
          "modelCode",
          "plateHint",
          "mileage",
          "preferredTimeText"
        ).contains(e.field()) ||
          (e.field().matches("symptoms\\.[0-9]+") &&
            Integer.parseInt(e.field().substring(9)) < d.symptoms().size())
      );
    }
    grounded(spans, "modelCode", h.modelCode());
    grounded(spans, "plateHint", h.plateHint());
    grounded(spans, "preferredTimeText", d.preferredTimeText());
    for (int i = 0; i < d.symptoms().size(); i++) grounded(
      spans,
      "symptoms." + i,
      d.symptoms().get(i)
    );
    if (h.mileage() != null) {
      var e = spans.get("mileage");
      check(e != null && supportedMileage(e.text(), h.mileage()));
    } else check(!spans.containsKey("mileage"));
    for (String field : d.missingFields())
      check(
        Set.of(
          "vehicleId",
          "address",
          "technicianId",
          "serviceStart",
          "items"
        ).contains(field)
      );
    if (d.preferredTimeRange() != null) {
      check(d.preferredTimeText() != null);
      try {
        String a = d.preferredTimeRange().earliest(),
          b = d.preferredTimeRange().latest();
        check(
          a != null &&
            b != null &&
            a.matches(".*T.*\\.\\d{3}Z") &&
            b.matches(".*T.*\\.\\d{3}Z")
        );
        Instant from = Instant.parse(a),
          to = Instant.parse(b);
        check(
          !to.isBefore(from) &&
            from.isAfter(now) &&
            to.isBefore(now.plus(Duration.ofDays(366)))
        );
        // 常见相对时间由确定性日历复核，不能采纳任意编造日期。
        var expected = relative(d.preferredTimeText(), now);
        if (expected == null) expected = absolute(d.preferredTimeText());
        check(
          expected != null && from.equals(expected[0]) && to.equals(expected[1])
        );
      } catch (DateTimeException e) {
        throw new DraftValidationException();
      }
    }
    return d;
  }

  public static Instant[] relative(String text, Instant now) {
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    LocalDate date = now.atZone(zone).toLocalDate();
    if (text.contains("后天")) date = date.plusDays(2);
    else if (text.contains("明天")) date = date.plusDays(1);
    else {
      Matcher m = Pattern.compile("(?:周|星期)([一二三四五六日天])").matcher(
        text
      );
      if (!m.find()) return null;
      int target = "一二三四五六日".indexOf(m.group(1)) + 1;
      if (target == 0) target = 7;
      if (text.contains("下周") || text.contains("下星期")) date = date
        .minusDays(date.getDayOfWeek().getValue() - 1)
        .plusDays(7L + target - 1);
      else {
        int delta = Math.floorMod(target - date.getDayOfWeek().getValue(), 7);
        if (delta == 0) delta = 7;
        date = date.plusDays(delta);
      }
    }
    int start, end;
    if (text.contains("上午")) {
      start = 8;
      end = 12;
    } else if (text.contains("下午")) {
      start = 13;
      end = 18;
    } else return null;
    return new Instant[] {
      date.atTime(start, 0).atZone(zone).toInstant(),
      date.atTime(end, 0).atZone(zone).toInstant(),
    };
  }

  private static Instant[] absolute(String text) {
    Matcher m = Pattern.compile(
      "([0-9]{4})[-年]([0-9]{1,2})[-月]([0-9]{1,2})(?:日)?"
    ).matcher(text);
    if (!m.find()) return null;
    LocalDate day = LocalDate.of(
      Integer.parseInt(m.group(1)),
      Integer.parseInt(m.group(2)),
      Integer.parseInt(m.group(3))
    );
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    if (text.contains("上午")) return new Instant[] {
      day.atTime(8, 0).atZone(zone).toInstant(),
      day.atTime(12, 0).atZone(zone).toInstant(),
    };
    if (text.contains("下午")) return new Instant[] {
      day.atTime(13, 0).atZone(zone).toInstant(),
      day.atTime(18, 0).atZone(zone).toInstant(),
    };
    Matcher time = Pattern.compile("([0-9]{1,2}):([0-9]{2})").matcher(text);
    if (!time.find()) return null;
    Instant instant = day
      .atTime(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)))
      .atZone(zone)
      .toInstant();
    return new Instant[] { instant, instant };
  }

  private static boolean supportedMileage(String raw, int value) {
    String v = raw.replaceAll("[\\s,，]", "").replaceAll("(?i)(公里|km)$", "");
    try {
      if (v.matches("[0-9]{1,7}")) return Integer.parseInt(v) == value;
      Matcher m = Pattern.compile(
        "([0-9]+(?:\\.[0-9]+)?)(万|千)([0-9]{1,4})?"
      ).matcher(v);
      if (!m.matches()) return false;
      double unit = m.group(2).equals("万") ? 10000 : 1000;
      double n = Double.parseDouble(m.group(1)) * unit;
      if (m.group(3) != null) n +=
        Integer.parseInt(m.group(3)) *
        (m.group(3).length() == 1 ? unit / 10 : 1);
      return n == value;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static void grounded(
    Map<String, BookingDraft.EvidenceSpan> spans,
    String key,
    String value
  ) {
    if (value == null) {
      check(!spans.containsKey(key));
      return;
    }
    var e = spans.get(key);
    check(e != null && value.equals(e.text()));
  }

  private static void optional(String v, int max) {
    if (v != null) check(
      !v.isBlank() && v.codePointCount(0, v.length()) <= max
    );
  }

  private static void strings(List<String> list, int max, int size) {
    check(list != null && list.size() <= max);
    for (var v : list) {
      check(v != null);
      optional(v, size);
    }
  }

  private static void check(boolean value) {
    if (!value) throw new DraftValidationException();
  }
}
