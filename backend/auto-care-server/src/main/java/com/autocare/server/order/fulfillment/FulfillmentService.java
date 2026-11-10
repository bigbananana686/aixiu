package com.autocare.server.order.fulfillment;

import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.command.*;
import com.autocare.server.order.*;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.order.lifecycle.*;
import com.autocare.server.order.quote.*;
import com.autocare.server.web.UtcTimes;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

@Service
public class FulfillmentService {

  private final OrderLifecycleMapper orders;
  private final OrderLifecycleService lifecycle;
  private final OrderMapper clock;
  private final QuoteMapper quotes;
  private final FulfillmentMapper writes;
  private final CommandExecutor commands;
  private final ObjectMapper json;
  private final com.autocare.server.order.exception.OrderExceptionService exceptions;

  public FulfillmentService(
    OrderLifecycleMapper orders,
    OrderLifecycleService lifecycle,
    OrderMapper clock,
    QuoteMapper quotes,
    FulfillmentMapper writes,
    CommandExecutor commands,
    ObjectMapper json,
    com.autocare.server.order.exception.OrderExceptionService exceptions
  ) {
    this.orders = orders;
    this.lifecycle = lifecycle;
    this.clock = clock;
    this.quotes = quotes;
    this.writes = writes;
    this.commands = commands;
    this.json = json;
    this.exceptions = exceptions;
  }

  public CommandResult execute(
    long actor,
    String path,
    String action,
    JsonNode body,
    String key,
    String requestId
  ) {
    Role role = action.equals("close") ? Role.OWNER : Role.TECH;
    lifecycle.currentRole(actor, role);
    long id = OrderInputs.id(path);
    FulfillmentInputs.Completion completion;
    int version;
    Map<String, Object> normalized = new LinkedHashMap<>();
    if (action.equals("complete")) {
      completion = FulfillmentInputs.completion(body);
      version = completion.version();
      normalized.put("actualFinish", UtcTimes.format(completion.finish()));
      if (completion.mileage() != null) normalized.put(
        "mileage",
        completion.mileage()
      );
      normalized.put("serviceResult", completion.result());
    } else {
      completion = null;
      FulfillmentInputs.object(body, Set.of("expectedRowVersion"));
      version = FulfillmentInputs.version(body);
    }
    normalized.put("expectedRowVersion", String.valueOf(version));
    OrderRow ref = orders.find(id);
    OrderLifecycleService.requireVisible(ref, actor, role);
    String operation = switch (action) {
      case "start" -> "startOrderService";
      case "complete" -> "completeOrder";
      case "close" -> "closeOrder";
      default -> throw new IllegalArgumentException("action");
    };
    return commands.execute(
      CommandSpec.of(operation)
        .pathResource("orderId", String.valueOf(id))
        .orderLinked(),
      key,
      normalized,
      requestId,
      () ->
        transition(
          actor,
          ref.getTechnicianId(),
          id,
          role,
          action,
          version,
          completion,
          requestId
        ),
      (who, resource) ->
        resource != null &&
        OrderLifecycleService.visible(orders.find(resource), who, role)
    );
  }

  private CommandOutcome<?> transition(
    long actor,
    long tech,
    long id,
    Role role,
    String action,
    int version,
    FulfillmentInputs.Completion completion,
    String requestId
  ) {
    orders.lockTechnician(tech);
    OrderRow row = orders.lockOrder(id);
    OrderLifecycleService.requireVisible(row, actor, role);
    lifecycle.currentRole(actor, role);
    String from = switch (action) {
      case "start" -> "READY_FOR_SERVICE";
      case "complete" -> "SERVICING";
      default -> "COMPLETED";
    };
    if (
      !from.equals(row.getState()) ||
      row.getPendingQuoteId() != null ||
      row.getApprovedQuoteId() == null
    ) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT);
    if (version != row.getRowVersion()) throw ApiException.of(
      ErrorCode.ORDER_VERSION_CONFLICT
    );
    LocalDateTime now = clock.dbNowUtc();
    Map<String, Object> result = new LinkedHashMap<>();
    String to, log;
    if (action.equals("start")) {
      // 资源→订单→批准报价；批准快照与订单规则是时间判定依据，不重新读取现价/工时。
      QuoteRow approved = quotes.lock(id, row.getApprovedQuoteId());
      if (
        approved == null || !"CONFIRMED".equals(approved.getStatus())
      ) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT);
      now = clock.dbNowUtc();
      RulesSnapshot rules = json.readValue(
        row.getRulesSnapshotJson(),
        RulesSnapshot.class
      );
      if (
        now
          .plusMinutes(
            (long) approved.getTotalMinutes() + rules.bufferAfterMinutes()
          )
          .isAfter(row.getCommittedEnd())
      ) throw ApiException.of(ErrorCode.SERVICE_TIME_INSUFFICIENT);
      checked(writes.start(id, version, now));
      to = "SERVICING";
      log = "START_ORDER";
      result.put("actualStart", UtcTimes.format(now));
      result.put("startedAt", UtcTimes.format(now));
    } else if (action.equals("complete")) {
      if (row.getActualStart() == null) throw ApiException.of(
        ErrorCode.ORDER_STATE_CONFLICT
      );
      if (
        completion.finish().isBefore(row.getActualStart()) ||
        completion.finish().isAfter(now)
      ) throw ApiException.invalid(
        FieldErrors.outOfRange(
          "/actualFinish",
          "不得早于开工或晚于锁内当前时间"
        )
      );
      checked(
        writes.complete(
          id,
          version,
          completion.finish(),
          completion.result(),
          completion.mileage(),
          now
        )
      );
      result.put(
        "overPlan",
        exceptions.completion(
          id,
          requestId,
          row.getCommittedEnd(),
          completion.finish(),
          now
        )
      );
      to = "COMPLETED";
      log = "COMPLETE_ORDER";
      result.put("actualStart", UtcTimes.format(row.getActualStart()));
      result.put("actualFinish", UtcTimes.format(completion.finish()));
      result.put("serviceResult", completion.result());
      result.put(
        "mileageSnapshot",
        completion.mileage() == null
          ? row.getMileageSnapshot()
          : completion.mileage()
      );
      result.put("completedAt", UtcTimes.format(completion.finish()));
    } else {
      checked(
        orders.transition(id, row.getState(), version, "CLOSED", true, now)
      );
      to = "CLOSED";
      log = "CLOSE_ORDER";
      result.put("approvedQuoteId", String.valueOf(row.getApprovedQuoteId()));
      result.put("pendingQuoteId", null);
      result.put("actualStart", UtcTimes.format(row.getActualStart()));
      result.put("actualFinish", UtcTimes.format(row.getActualFinish()));
      result.put("reservationActive", true);
      result.put("changedAt", UtcTimes.format(now));
    }
    orders.insertStateLog(id, from, to, log, actor, requestId, now);
    orders.insertOperation(id, log, actor, null, requestId, now);
    result.put("orderId", String.valueOf(id));
    result.put("state", to);
    result.put("rowVersion", String.valueOf(version + 1));
    result.put("committedEnd", UtcTimes.format(row.getCommittedEnd()));
    result.put("effectiveEnd", UtcTimes.format(row.getEffectiveEnd()));
    return CommandOutcome.ok(result, id);
  }

  private static void checked(int n) {
    if (n != 1) throw new IllegalStateException(
      "Locked fulfillment update affected no row"
    );
  }
}
