package com.autocare.server.availability.admin;

import static com.autocare.server.web.InputValues.id;

import com.autocare.common.error.*;
import com.autocare.server.command.*;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

@Service
public class ScheduleService {

  private final ScheduleMapper mapper;
  private final AdminAccess access;
  private final CommandExecutor commands;
  private final OrderMapper clock;

  public ScheduleService(
    ScheduleMapper mapper,
    AdminAccess access,
    CommandExecutor commands,
    OrderMapper clock
  ) {
    this.mapper = mapper;
    this.access = access;
    this.commands = commands;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(
    long actor,
    MultiValueMap<String, String> query
  ) {
    access.require(actor);
    ListInputs q = ListInputs.parse(query);
    return Map.of(
      "items",
      mapper
        .list(q.enabled(), q.pageSize(), q.offset())
        .stream()
        .map(ScheduleService::techView)
        .toList(),
      "page",
      q.page(),
      "pageSize",
      q.pageSize(),
      "total",
      mapper.count(q.enabled())
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> get(
    long actor,
    String path,
    MultiValueMap<String, String> query
  ) {
    access.require(actor);
    long id = id(path, "technicianId");
    for (var e : query.entrySet())
      if (
        !Set.of("rangeStart", "rangeEnd").contains(e.getKey()) ||
        e.getValue().size() != 1
      ) throw ApiException.invalid(
        FieldErrors.notAllowed("/" + e.getKey(), "未知或重复参数")
      );
    WindowInputs.Range r = WindowInputs.range(
      query.getFirst("rangeStart"),
      query.getFirst("rangeEnd")
    );
    ScheduleRow row = required(mapper.find(id));
    return result(
      id,
      r,
      row.getScheduleVersion(),
      all(id)
        .stream()
        .filter(w -> w.start().isBefore(r.end()) && w.end().isAfter(r.start()))
        .map(w ->
          new WindowInputs.Window(
            w.start().isBefore(r.start()) ? r.start() : w.start(),
            w.end().isAfter(r.end()) ? r.end() : w.end()
          )
        )
        .toList(),
      null
    );
  }

  public CommandResult replace(
    long actor,
    String path,
    JsonNode body,
    String key,
    String requestId
  ) {
    access.require(actor);
    long id = id(path, "technicianId");
    WindowInputs.Replacement p = WindowInputs.replacement(body);
    required(mapper.find(id));
    Map<String, Object> n = Map.of(
      "rangeStart",
      p.range().from().toString(),
      "rangeEnd",
      p.range().to().toString(),
      "expectedScheduleVersion",
      String.valueOf(p.version()),
      "windows",
      p.windows().stream().map(WindowInputs.Window::view).toList()
    );
    return commands.execute(
      CommandSpec.of("replaceTechnicianWindows").pathResource(
        "technicianId",
        String.valueOf(id)
      ),
      key,
      n,
      requestId,
      () -> {
        access.require(actor);
        ScheduleRow row = required(mapper.lock(id));
        if (row.getScheduleVersion() != p.version()) throw ApiException.of(
          ErrorCode.RESOURCE_VERSION_CONFLICT
        ).field(
          FieldErrors.versionMismatch(
            "/expectedScheduleVersion",
            "排期版本不一致"
          )
        );
        if (
          row.getScheduleVersion() == Integer.MAX_VALUE
        ) throw new IllegalStateException(
          "Schedule version exhausted; operator action required"
        );
        List<WindowInputs.Window> changed = new ArrayList<>(p.windows()),
          resulting = new ArrayList<>();
        for (var old : all(id)) {
          if (
            !old.start().isBefore(p.range().end()) ||
            !old.end().isAfter(p.range().start())
          ) {
            resulting.add(old);
            continue;
          }
          // 跨日期边界的既有窗口只替换范围内部分，保留两侧的可用时间。
          if (old.start().isBefore(p.range().start())) changed.add(
            new WindowInputs.Window(old.start(), p.range().start())
          );
          if (old.end().isAfter(p.range().end())) changed.add(
            new WindowInputs.Window(p.range().end(), old.end())
          );
        }
        resulting.addAll(changed);
        for (var reservation : mapper.reservations(id))
          if (
            resulting
              .stream()
              .noneMatch(w ->
                w.covers(
                  reservation.getOccupiedStart(),
                  reservation.getEffectiveEnd()
                )
              )
          ) throw ApiException.of(ErrorCode.WORK_WINDOW_CONFLICT);
        LocalDateTime now = clock.dbNowUtc();
        mapper.removeRange(id, p.range().start(), p.range().end());
        for (var window : changed)
          mapper.insert(id, window.start(), window.end(), now);
        if (
          mapper.advance(id, p.version(), now) != 1
        ) throw new IllegalStateException(
          "Locked schedule write affected no row"
        );
        return CommandOutcome.ok(
          result(id, p.range(), p.version() + 1, p.windows(), now),
          id
        );
      },
      (who, resource) -> {
        access.require(who);
        return resource != null && mapper.find(resource) != null;
      }
    );
  }

  private List<WindowInputs.Window> all(long id) {
    return mapper
      .windows(id)
      .stream()
      .map(w -> new WindowInputs.Window(w.getWindowStart(), w.getWindowEnd()))
      .toList();
  }

  private static ScheduleRow required(ScheduleRow row) {
    if (row == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    return row;
  }

  private static Map<String, Object> techView(ScheduleRow r) {
    return Map.of(
      "technicianId",
      String.valueOf(r.getTechnicianId()),
      "displayName",
      r.getDisplayName(),
      "enabled",
      r.getEnabled(),
      "scheduleVersion",
      String.valueOf(r.getScheduleVersion())
    );
  }

  private static Map<String, Object> result(
    long id,
    WindowInputs.Range range,
    int version,
    List<WindowInputs.Window> windows,
    LocalDateTime now
  ) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("technicianId", String.valueOf(id));
    m.put("rangeStart", range.from().toString());
    m.put("rangeEnd", range.to().toString());
    m.put("scheduleVersion", String.valueOf(version));
    m.put("windows", windows.stream().map(WindowInputs.Window::view).toList());
    if (now != null) m.put("changedAt", UtcTimes.format(now));
    return m;
  }
}
