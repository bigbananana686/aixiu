package com.autocare.server.order.reschedule;

import static com.autocare.server.web.InputValues.*;

import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.command.*;
import com.autocare.server.order.*;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.order.lifecycle.*;
import com.autocare.server.order.quote.QuoteMapper;
import com.autocare.server.web.UtcTimes;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.*;

@Service
public class RescheduleService {

  private final OrderLifecycleMapper orders;
  private final OrderLifecycleService lifecycle;
  private final OrderMapper booking;
  private final QuoteMapper conflicts;
  private final RescheduleMapper mapper;
  private final CommandExecutor commands;
  private final ObjectMapper json;

  public RescheduleService(
    OrderLifecycleMapper orders,
    OrderLifecycleService lifecycle,
    OrderMapper booking,
    QuoteMapper conflicts,
    RescheduleMapper mapper,
    CommandExecutor commands,
    ObjectMapper json
  ) {
    this.orders = orders;
    this.lifecycle = lifecycle;
    this.booking = booking;
    this.conflicts = conflicts;
    this.mapper = mapper;
    this.commands = commands;
    this.json = json;
  }

  public CommandResult execute(
    long actor,
    String path,
    JsonNode body,
    String key,
    String requestId
  ) {
    lifecycle.currentRole(actor, Role.OWNER);
    long id = id(path, "orderId");
    object(body, Set.of("expectedRowVersion", "serviceStart"));
    int version = version(body, "expectedRowVersion");
    LocalDateTime start = time(body, "serviceStart");
    OrderRow ref = orders.find(id);
    OrderLifecycleService.requireVisible(ref, actor, Role.OWNER);
    Map<String, Object> n = Map.of(
      "expectedRowVersion",
      String.valueOf(version),
      "serviceStart",
      UtcTimes.format(start)
    );
    return commands.execute(
      CommandSpec.of("rescheduleOrder")
        .pathResource("orderId", String.valueOf(id))
        .orderLinked(),
      key,
      n,
      requestId,
      () -> {
        orders.lockTechnician(ref.getTechnicianId());
        OrderRow row = orders.lockOrder(id);
        OrderLifecycleService.requireVisible(row, actor, Role.OWNER);
        lifecycle.currentRole(actor, Role.OWNER);
        if (row.getRowVersion() != version) throw ApiException.of(
          ErrorCode.ORDER_VERSION_CONFLICT
        );
        if (
          !Set.of("RESERVED", "ACCEPTED").contains(row.getState())
        ) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT);
        RulesSnapshot rules = json.readValue(
          row.getRulesSnapshotJson(),
          RulesSnapshot.class
        );
        List<BookingItemSnapshot> items = json.readValue(
          row.getBookingItemsJson(),
          new TypeReference<List<BookingItemSnapshot>>() {}
        );
        long minutes = items
          .stream()
          .mapToLong(i -> (long) i.durationMinutes() * i.quantity())
          .sum();
        LocalDateTime occupied = start.minusMinutes(
            (long) rules.commuteMinutes() + rules.bufferBeforeMinutes()
          ),
          end = start.plusMinutes(minutes + rules.bufferAfterMinutes()),
          now = booking.dbNowUtc();
        if (
          occupied.isBefore(now) ||
          booking.countWindowsCovering(row.getTechnicianId(), occupied, end) ==
            0
        ) throw ApiException.of(ErrorCode.WORK_WINDOW_VIOLATION);
        if (
          conflicts.conflicts(row.getTechnicianId(), id, occupied, end) > 0
        ) throw ApiException.of(ErrorCode.SLOT_CONFLICT);
        if (row.getPendingQuoteId() != null) throw ApiException.of(
          ErrorCode.ORDER_STATE_CONFLICT
        );
        if (
          mapper.update(id, version, start, occupied, end, now) != 1
        ) throw new IllegalStateException("Locked reschedule affected no row");
        // 改期不伪造状态迁移、不续接单截止；只留下新旧预约时间的脱敏操作摘要。
        orders.insertOperation(
          id,
          "RESCHEDULE_ORDER",
          actor,
          json.writeValueAsString(
            Map.of(
              "oldServiceStart",
              UtcTimes.format(row.getServiceStart()),
              "newServiceStart",
              UtcTimes.format(start)
            )
          ),
          requestId,
          now
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", String.valueOf(id));
        result.put("state", row.getState());
        result.put("rowVersion", String.valueOf(version + 1));
        result.put("serviceStart", UtcTimes.format(start));
        result.put("occupiedStart", UtcTimes.format(occupied));
        result.put("committedEnd", UtcTimes.format(end));
        result.put("effectiveEnd", UtcTimes.format(end));
        result.put("acceptDeadline", UtcTimes.format(row.getAcceptDeadline()));
        result.put("reservationActive", row.getReservationActive());
        result.put("changedAt", UtcTimes.format(now));
        return CommandOutcome.ok(result, id);
      },
      (who, resource) ->
        resource != null &&
        OrderLifecycleService.visible(orders.find(resource), who, Role.OWNER)
    );
  }
}
