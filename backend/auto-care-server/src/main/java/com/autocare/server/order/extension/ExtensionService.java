package com.autocare.server.order.extension;

import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.command.*;
import com.autocare.server.order.*;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.order.exception.OrderExceptionService;
import com.autocare.server.order.lifecycle.*;
import com.autocare.server.order.quote.QuoteMapper;
import com.autocare.server.web.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class ExtensionService {

  private final OrderLifecycleMapper orders;
  private final OrderLifecycleService lifecycle;
  private final OrderMapper resources;
  private final QuoteMapper quotes;
  private final CommandExecutor commands;
  private final OrderExceptionService exceptions;

  public ExtensionService(
    OrderLifecycleMapper orders,
    OrderLifecycleService lifecycle,
    OrderMapper resources,
    QuoteMapper quotes,
    CommandExecutor commands,
    OrderExceptionService exceptions
  ) {
    this.orders = orders;
    this.lifecycle = lifecycle;
    this.resources = resources;
    this.quotes = quotes;
    this.commands = commands;
    this.exceptions = exceptions;
  }

  public CommandResult execute(
    long actor,
    String path,
    JsonNode body,
    String key,
    String request
  ) {
    lifecycle.currentRole(actor, Role.TECH);
    long id = OrderInputs.id(path);
    InputValues.object(
      body,
      Set.of("expectedRowVersion", "targetEnd", "reason")
    );
    int version = InputValues.version(body, "expectedRowVersion");
    LocalDateTime target = InputValues.time(body, "targetEnd");
    String reason = OrderInputs.reason(body.get("reason"), body.has("reason"));
    OrderRow ref = orders.find(id);
    OrderLifecycleService.requireVisible(ref, actor, Role.TECH);
    var normalized = Map.of(
      "expectedRowVersion",
      "" + version,
      "targetEnd",
      UtcTimes.format(target),
      "reason",
      reason
    );
    try {
      return commands.execute(
        CommandSpec.of("extendOrderRuntime")
          .pathResource("orderId", "" + id)
          .orderLinked(),
        key,
        normalized,
        request,
        () -> extend(actor, ref, id, version, target, reason, request),
        (who, resource) ->
          resource != null &&
          OrderLifecycleService.visible(orders.find(resource), who, Role.TECH)
      );
    } catch (ApiException e) {
      if (e.errorCode() == ErrorCode.SLOT_CONFLICT) e.detail(
        "exceptionRecorded",
        exceptions.deferral(id, request, target)
      );
      throw e;
    }
  }

  private CommandOutcome<?> extend(
    long actor,
    OrderRow ref,
    long id,
    int version,
    LocalDateTime target,
    String reason,
    String request
  ) {
    orders.lockTechnician(ref.getTechnicianId());
    OrderRow r = orders.lockOrder(id);
    OrderLifecycleService.requireVisible(r, actor, Role.TECH);
    lifecycle.currentRole(actor, Role.TECH);
    if (r.getRowVersion() != version) throw ApiException.of(
      ErrorCode.ORDER_VERSION_CONFLICT
    );
    if (
      r.getPendingQuoteId() != null ||
      !Set.of("READY_FOR_SERVICE", "SERVICING").contains(r.getState())
    ) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT).detail(
      "currentState",
      r.getState()
    );
    if (!target.isAfter(r.getCommittedEnd())) throw ApiException.invalid(
      FieldErrors.outOfRange("/targetEnd", "必须严格晚于已承诺结束")
    );
    if (
      resources.countWindowsCovering(
        r.getTechnicianId(),
        r.getOccupiedStart(),
        target
      ) == 0
    ) throw ApiException.of(ErrorCode.WORK_WINDOW_VIOLATION);
    if (
      quotes.conflicts(r.getTechnicianId(), id, r.getOccupiedStart(), target) >
      0
    ) throw ApiException.of(ErrorCode.SLOT_CONFLICT);
    LocalDateTime now = resources.dbNowUtc();
    if (
      quotes.updateOrder(
        id,
        version,
        r.getState(),
        r.getApprovedQuoteId(),
        null,
        target,
        target,
        now
      ) != 1
    ) throw new IllegalStateException("Locked extension update failed");
    if (
      orders.insertOperation(
        id,
        "EXTEND_RUNTIME",
        actor,
        reason,
        request,
        now
      ) != 1
    ) throw new IllegalStateException("Extension audit failed");
    return CommandOutcome.ok(
      Map.of(
        "orderId",
        "" + id,
        "state",
        r.getState(),
        "rowVersion",
        "" + (version + 1),
        "previousCommittedEnd",
        UtcTimes.format(r.getCommittedEnd()),
        "committedEnd",
        UtcTimes.format(target),
        "effectiveEnd",
        UtcTimes.format(target),
        "reason",
        reason,
        "changedAt",
        UtcTimes.format(now)
      ),
      id
    );
  }
}
