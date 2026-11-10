package com.autocare.server.order.quote;

import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.command.*;
import com.autocare.server.order.OrderRow;
import com.autocare.server.order.RulesSnapshot;
import com.autocare.server.order.booking.CatalogQuoteRow;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.order.lifecycle.OrderLifecycleMapper;
import com.autocare.server.order.lifecycle.OrderLifecycleService;
import com.autocare.server.web.UtcTimes;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

@Service
public class QuoteService {

  private final QuoteMapper quotes;
  private final OrderLifecycleMapper orders;
  private final OrderMapper resources;
  private final OrderLifecycleService lifecycle;
  private final CommandExecutor commands;
  private final ObjectMapper json;
  private final RequestDigest digest;
  private final org.springframework.beans.factory.ObjectProvider<QuoteExpiryService> expiry;

  public QuoteService(
    QuoteMapper quotes,
    OrderLifecycleMapper orders,
    OrderMapper resources,
    OrderLifecycleService lifecycle,
    CommandExecutor commands,
    ObjectMapper json,
    RequestDigest digest,
    org.springframework.beans.factory.ObjectProvider<QuoteExpiryService> expiry
  ) {
    this.quotes = quotes;
    this.orders = orders;
    this.resources = resources;
    this.lifecycle = lifecycle;
    this.commands = commands;
    this.json = json;
    this.digest = digest;
    this.expiry = expiry;
  }

  public CommandResult submit(
    long actor,
    String orderId,
    JsonNode body,
    String key,
    String requestId
  ) {
    lifecycle.currentRole(actor, Role.TECH);
    long id = QuoteInputs.pathId(orderId, "orderId");
    QuoteInputs.Submission input = QuoteInputs.submission(body);
    OrderRow reference = orders.find(id);
    OrderLifecycleService.requireVisible(reference, actor, Role.TECH);
    try {
      return commands.execute(
        CommandSpec.of("submitOrderQuote")
          .pathResource("orderId", "" + id)
          .orderLinked(),
        key,
        input,
        requestId,
        () -> publish(actor, reference, id, input, requestId),
        (who, resource) ->
          resource != null &&
          OrderLifecycleService.visible(orders.find(resource), who, Role.TECH)
      );
    } catch (ApiException e) {
      // 替换已到期：命令失败/幂等登记先回滚，再以同一锁协议终结旧轮次。
      if (
        e.errorCode() == ErrorCode.QUOTE_EXPIRED &&
        input.expectedPendingQuoteId() != null
      ) {
        long qid = Long.parseLong(input.expectedPendingQuoteId());
        QuoteRow q = quotes.find(id, qid);
        if (q != null) expiry.getObject().expire(id, qid, q.getVersion());
      }
      throw e;
    }
  }

  private CommandOutcome<?> publish(
    long actor,
    OrderRow reference,
    long id,
    QuoteInputs.Submission input,
    String requestId
  ) {
    orders.lockTechnician(reference.getTechnicianId());
    OrderRow r = orders.lockOrder(id);
    OrderLifecycleService.requireVisible(r, actor, Role.TECH);
    lifecycle.currentRole(actor, Role.TECH);
    if (
      r.getRowVersion() != Integer.parseInt(input.expectedRowVersion())
    ) throw ApiException.of(ErrorCode.ORDER_VERSION_CONFLICT);
    boolean replacing = "QUOTE_PENDING".equals(r.getState());
    boolean extra = "EXTRA".equals(input.purpose());
    if (
      !replacing && !(extra ? "SERVICING" : "ARRIVED").equals(r.getState())
    ) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT);
    if (
      !Objects.equals(
        OrderLifecycleService.nullableId(r.getPendingQuoteId()),
        input.expectedPendingQuoteId()
      )
    ) throw baseline("/expectedPendingQuoteId", "待确认基线不一致");
    QuoteRow pending =
      r.getPendingQuoteId() == null
        ? null
        : quotes.lock(id, r.getPendingQuoteId());
    if (
      replacing && (pending == null || !"PENDING".equals(pending.getStatus()))
    ) throw baseline("/expectedPendingQuoteId", "没有当前待确认报价");
    LocalDateTime now = resources.dbNowUtc();
    if (pending != null && !now.isBefore(pending.getExpiresAt())) throw expired(
      pending
    );
    if (
      pending != null && !input.purpose().equals(pending.getPurpose())
    ) throw baseline("/purpose", "替换必须保持本轮用途");
    if (
      !Objects.equals(
        OrderLifecycleService.nullableId(r.getApprovedQuoteId()),
        input.expectedApprovedQuoteId()
      )
    ) throw baseline("/expectedApprovedQuoteId", "批准基线不一致");
    if (extra != (r.getApprovedQuoteId() != null)) throw baseline(
      "/expectedApprovedQuoteId",
      "用途与批准基线不一致"
    );
    Map<String, QuoteItemSnapshot> inherited = new LinkedHashMap<>();
    if (extra) {
      QuoteRow approved = quotes.lock(id, r.getApprovedQuoteId());
      if (
        approved == null || !"CONFIRMED".equals(approved.getStatus())
      ) throw baseline("/expectedApprovedQuoteId", "批准版本无效");
      for (var item : quotes.items(approved.getId()))
        inherited.put(item.lineKey(), item);
      for (var old : inherited.values()) {
        var current = input
          .items()
          .stream()
          .filter(i -> i.lineKey().equals(old.lineKey()))
          .findFirst()
          .orElse(null);
        if (current == null) throw baseline(
          "/items/0/lineKey",
          "缺少已批准条目 " + old.lineKey()
        )
          .detail("approvedQuoteId", "" + approved.getId())
          .detail(
            "missingLineKeys",
            inherited
              .keySet()
              .stream()
              .filter(k ->
                input
                  .items()
                  .stream()
                  .noneMatch(i -> i.lineKey().equals(k))
              )
              .toList()
          );
        int index = input.items().indexOf(current);
        if (
          !current.serviceItemId().equals(old.serviceItemId())
        ) throw baseline(
          "/items/" + index + "/serviceItemId",
          "不得更换已批准项目"
        );
        if (current.quantity() != old.quantity()) throw baseline(
          "/items/" + index + "/quantity",
          "不得修改已批准数量"
        );
      }
    }
    var ids = input
      .items()
      .stream()
      .filter(i -> !inherited.containsKey(i.lineKey()))
      .map(i -> Long.parseLong(i.serviceItemId()))
      .distinct()
      .sorted()
      .toList();
    Map<Long, CatalogQuoteRow> catalog = new HashMap<>();
    if (!ids.isEmpty()) for (var c : resources.findCatalogForShare(ids))
      catalog.put(c.getId(), c);
    List<QuoteItemSnapshot> items = new ArrayList<>();
    BigDecimal amount = BigDecimal.ZERO;
    int minutes = 0,
      added = 0;
    for (var i : input.items()) {
      QuoteItemSnapshot old = inherited.get(i.lineKey());
      if (old != null) {
        items.add(old);
        minutes = Math.addExact(minutes, old.lineMinutes());
        amount = amount.add(
          new BigDecimal(old.unitPrice()).multiply(
            BigDecimal.valueOf(old.quantity())
          )
        );
        continue;
      }
      var c = catalog.get(Long.parseLong(i.serviceItemId()));
      if (
        c == null || !Boolean.TRUE.equals(c.getEnabled())
      ) throw ApiException.of(ErrorCode.SERVICE_ITEM_UNAVAILABLE);
      if (
        !i.catalogVersion().equals("" + c.getVersion())
      ) throw ApiException.of(ErrorCode.CATALOG_VERSION_CONFLICT);
      int line = c.getDurationMinutes() * i.quantity();
      minutes = Math.addExact(minutes, line);
      if (extra) added = Math.addExact(added, line);
      amount = amount.add(
        c.getPrice().multiply(BigDecimal.valueOf(i.quantity()))
      );
      items.add(
        new QuoteItemSnapshot(
          i.lineKey(),
          i.serviceItemId(),
          c.getName(),
          c.getPrice().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
          c.getCurrency(),
          i.quantity(),
          c.getDurationMinutes(),
          line,
          extra
        )
      );
    }
    if (
      minutes > 10080 || amount.compareTo(new BigDecimal("9999999.99")) > 0
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/items", "总金额或总工时超出范围")
    );
    RulesSnapshot rules = json.readValue(
      r.getRulesSnapshotJson(),
      RulesSnapshot.class
    );
    // 目录锁可能等待，取得所有业务锁之后再读取数据库时间。
    now = resources.dbNowUtc();
    if (pending != null && !now.isBefore(pending.getExpiresAt())) throw expired(
      pending
    );
    LocalDateTime target = extra
      ? r.getCommittedEnd().plusMinutes(added)
      : r
          .getServiceStart()
          .plusMinutes((long) minutes + rules.bufferAfterMinutes());
    LocalDateTime effective = max(r.getCommittedEnd(), target);
    if (
      resources.countWindowsCovering(
        r.getTechnicianId(),
        r.getOccupiedStart(),
        effective
      ) == 0
    ) throw ApiException.of(ErrorCode.WORK_WINDOW_VIOLATION);
    if (
      quotes.conflicts(
        r.getTechnicianId(),
        id,
        r.getOccupiedStart(),
        effective
      ) > 0
    ) throw ApiException.of(ErrorCode.SLOT_CONFLICT);
    QuoteRow q = new QuoteRow();
    q.setOrderId(id);
    q.setVersion(quotes.nextVersion(id));
    q.setPurpose(input.purpose());
    q.setBaseApprovedQuoteId(r.getApprovedQuoteId());
    q.setAddedMinutes(added);
    q.setStatus("PENDING");
    q.setTotalAmount(amount.setScale(2));
    q.setCurrency("CNY");
    q.setTotalMinutes(minutes);
    q.setTargetEnd(target);
    q.setExpiresAt(
      pending == null
        ? now.plusMinutes(rules.quoteConfirmWindowMinutes())
        : pending.getExpiresAt()
    );
    q.setSubmittedBy(actor);
    q.setSubmittedAt(now);
    // 固定内容对象包含完整快照；复用既有稳定规范化/SHA-256算法，不含时间、认证或requestId。
    q.setContentHash(
      digest.compute(
        CommandSpec.of("quoteContent"),
        Map.of("purpose", input.purpose(), "items", items)
      )
    );
    if (pending != null) requireOne(
      quotes.terminate(
        pending.getId(),
        "SUPERSEDED",
        null,
        null,
        input.reason()
      )
    );
    requireOne(quotes.insert(q));
    requireOne(quotes.advanceSequence(id, q.getVersion()));
    for (var item : items) requireOne(quotes.insertItem(q.getId(), item));
    writeOrder(
      r,
      "QUOTE_PENDING",
      r.getApprovedQuoteId(),
      q.getId(),
      r.getCommittedEnd(),
      effective,
      replacing ? "REPLACE_QUOTE" : "SUBMIT_QUOTE",
      q,
      actor,
      input.reason(),
      requestId,
      now
    );
    Map<String, Object> result = orderResult(
      r,
      "QUOTE_PENDING",
      r.getApprovedQuoteId(),
      q.getId(),
      r.getCommittedEnd(),
      effective
    );
    result.put("quote", QuoteView.from(q, List.copyOf(items)));
    return CommandOutcome.created(
      "/api/v1/orders/" + id + "/quotes/" + q.getId(),
      result,
      id
    );
  }

  public CommandResult decide(
    long actor,
    String orderId,
    String quoteId,
    JsonNode body,
    String key,
    String requestId,
    boolean reject
  ) {
    lifecycle.currentRole(actor, Role.OWNER);
    long id = QuoteInputs.pathId(orderId, "orderId"),
      qid = QuoteInputs.pathId(quoteId, "quoteId");
    var input = QuoteInputs.decision(body, reject);
    OrderRow reference = orders.find(id);
    OrderLifecycleService.requireVisible(reference, actor, Role.OWNER);
    if (quotes.find(id, qid) == null) throw ApiException.of(
      ErrorCode.RESOURCE_NOT_FOUND
    );
    Map<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("version", input.version());
    if (reject) normalized.put("reason", input.reason());
    return commands.execute(
      CommandSpec.of(reject ? "rejectOrderQuote" : "confirmOrderQuote")
        .pathResource("orderId", "" + id)
        .pathResource("quoteId", "" + qid)
        .orderLinked(),
      key,
      normalized,
      requestId,
      () -> decideLocked(actor, reference, id, qid, input, reject, requestId),
      (who, resource) ->
        resource != null &&
        OrderLifecycleService.visible(orders.find(resource), who, Role.OWNER) &&
        quotes.find(resource, qid) != null
    );
  }

  private CommandOutcome<?> decideLocked(
    long actor,
    OrderRow reference,
    long id,
    long qid,
    QuoteInputs.Decision input,
    boolean reject,
    String requestId
  ) {
    orders.lockTechnician(reference.getTechnicianId());
    OrderRow r = orders.lockOrder(id);
    OrderLifecycleService.requireVisible(r, actor, Role.OWNER);
    lifecycle.currentRole(actor, Role.OWNER);
    QuoteRow q = quotes.lock(id, qid);
    if (q == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    if (
      q.getVersion() != Integer.parseInt(input.version()) ||
      "SUPERSEDED".equals(q.getStatus())
    ) throw ApiException.of(ErrorCode.QUOTE_VERSION_CONFLICT);
    // EXPIRED/审批终态需保留其错误身份；只对仍PENDING的报价核对当前pending指针。
    if ("EXPIRED".equals(q.getStatus())) throw expired(q);
    if (!"PENDING".equals(q.getStatus())) throw ApiException.of(
      ErrorCode.QUOTE_STATE_CONFLICT
    );
    if (!Objects.equals(r.getPendingQuoteId(), qid)) throw ApiException.of(
      ErrorCode.QUOTE_VERSION_CONFLICT
    );
    LocalDateTime now = resources.dbNowUtc();
    if (!now.isBefore(q.getExpiresAt())) throw expired(q);
    if (
      !"QUOTE_PENDING".equals(r.getState()) ||
      ("EXTRA".equals(q.getPurpose()) &&
        !Objects.equals(q.getBaseApprovedQuoteId(), r.getApprovedQuoteId()))
    ) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT);
    String state = "EXTRA".equals(q.getPurpose())
      ? "SERVICING"
      : reject
        ? "ARRIVED"
        : "READY_FOR_SERVICE";
    Long approved = reject ? r.getApprovedQuoteId() : Long.valueOf(qid);
    LocalDateTime committed = reject
      ? r.getCommittedEnd()
      : max(r.getCommittedEnd(), q.getTargetEnd());
    requireOne(
      quotes.terminate(
        qid,
        reject ? "REJECTED" : "CONFIRMED",
        reject ? null : actor,
        reject ? null : now,
        input.reason()
      )
    );
    writeOrder(
      r,
      state,
      approved,
      null,
      committed,
      committed,
      reject ? "REJECT_QUOTE" : "CONFIRM_QUOTE",
      q,
      actor,
      input.reason(),
      requestId,
      now
    );
    Map<String, Object> result = orderResult(
      r,
      state,
      approved,
      null,
      committed,
      committed
    );
    result.put("quoteId", "" + qid);
    result.put("version", "" + q.getVersion());
    if (reject) result.put("quoteStatus", "REJECTED");
    return CommandOutcome.ok(result, id);
  }

  public CommandResult withdraw(
    long actor,
    String orderId,
    String quoteId,
    JsonNode body,
    String key,
    String requestId
  ) {
    lifecycle.currentRole(actor, Role.TECH);
    long id = QuoteInputs.pathId(orderId, "orderId"),
      qid = QuoteInputs.pathId(quoteId, "quoteId");
    var input = QuoteInputs.decision(body, false);
    OrderRow reference = orders.find(id);
    OrderLifecycleService.requireVisible(reference, actor, Role.TECH);
    if (quotes.find(id, qid) == null) throw ApiException.of(
      ErrorCode.RESOURCE_NOT_FOUND
    );
    try {
      return commands.execute(
        CommandSpec.of("withdrawOrderQuote")
          .pathResource("orderId", "" + id)
          .pathResource("quoteId", "" + qid)
          .orderLinked(),
        key,
        Map.of("version", input.version()),
        requestId,
        () -> withdrawLocked(actor, reference, id, qid, input, requestId),
        (who, resource) ->
          resource != null &&
          OrderLifecycleService.visible(
            orders.find(resource),
            who,
            Role.TECH
          ) &&
          quotes.find(resource, qid) != null
      );
    } catch (ApiException e) {
      // 已到期的轮次交给既有到期服务处理；命令事务已回滚，不把到期冒充撤回成功。
      if (e.errorCode() == ErrorCode.QUOTE_EXPIRED) {
        QuoteRow q = quotes.find(id, qid);
        if (q != null) expiry.getObject().expire(id, qid, q.getVersion());
      }
      throw e;
    }
  }

  private CommandOutcome<?> withdrawLocked(
    long actor,
    OrderRow reference,
    long id,
    long qid,
    QuoteInputs.Decision input,
    String requestId
  ) {
    orders.lockTechnician(reference.getTechnicianId());
    OrderRow r = orders.lockOrder(id);
    OrderLifecycleService.requireVisible(r, actor, Role.TECH);
    lifecycle.currentRole(actor, Role.TECH);
    QuoteRow q = quotes.lock(id, qid);
    if (q == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    if (
      q.getVersion() != Integer.parseInt(input.version()) ||
      "SUPERSEDED".equals(q.getStatus())
    ) throw ApiException.of(ErrorCode.QUOTE_VERSION_CONFLICT);
    if ("EXPIRED".equals(q.getStatus())) throw expired(q);
    if (!"PENDING".equals(q.getStatus())) throw ApiException.of(
      ErrorCode.QUOTE_STATE_CONFLICT
    );
    if (!Objects.equals(r.getPendingQuoteId(), qid)) throw ApiException.of(
      ErrorCode.QUOTE_VERSION_CONFLICT
    );
    if (
      !"QUOTE_PENDING".equals(r.getState()) ||
      ("EXTRA".equals(q.getPurpose()) &&
        !Objects.equals(q.getBaseApprovedQuoteId(), r.getApprovedQuoteId()))
    ) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT);
    LocalDateTime now = resources.dbNowUtc();
    if (!now.isBefore(q.getExpiresAt())) throw expired(q);
    String state = "EXTRA".equals(q.getPurpose()) ? "SERVICING" : "ARRIVED";
    requireOne(
      quotes.terminate(
        qid,
        "WITHDRAWN",
        null,
        null,
        "withdrawn by assigned technician"
      )
    );
    writeOrder(
      r,
      state,
      r.getApprovedQuoteId(),
      null,
      r.getCommittedEnd(),
      r.getCommittedEnd(),
      "WITHDRAW_QUOTE",
      q,
      actor,
      null,
      requestId,
      now
    );
    Map<String, Object> result = orderResult(
      r,
      state,
      r.getApprovedQuoteId(),
      null,
      r.getCommittedEnd(),
      r.getCommittedEnd()
    );
    result.put("quoteId", "" + qid);
    result.put("version", "" + q.getVersion());
    result.put("quoteStatus", "WITHDRAWN");
    return CommandOutcome.ok(result, id);
  }

  void writeOrder(
    OrderRow r,
    String state,
    Long approved,
    Long pending,
    LocalDateTime committed,
    LocalDateTime effective,
    String action,
    QuoteRow q,
    long actor,
    String reason,
    String requestId,
    LocalDateTime now
  ) {
    requireOne(
      quotes.updateOrder(
        r.getId(),
        r.getRowVersion(),
        state,
        approved,
        pending,
        committed,
        effective,
        now
      )
    );
    if (!r.getState().equals(state)) requireOne(
      orders.insertStateLog(
        r.getId(),
        r.getState(),
        state,
        action,
        actor,
        requestId,
        now
      )
    );
    requireOne(
      quotes.operation(
        r.getId(),
        action,
        q.getId(),
        actor,
        "quoteId=" + q.getId() + " version=" + q.getVersion(),
        requestId,
        now
      )
    );
  }

  static Map<String, Object> orderResult(
    OrderRow r,
    String state,
    Long approved,
    Long pending,
    LocalDateTime committed,
    LocalDateTime effective
  ) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderId", "" + r.getId());
    m.put("rowVersion", "" + (r.getRowVersion() + 1));
    m.put("state", state);
    m.put("approvedQuoteId", OrderLifecycleService.nullableId(approved));
    m.put("pendingQuoteId", OrderLifecycleService.nullableId(pending));
    m.put("committedEnd", UtcTimes.format(committed));
    m.put("effectiveEnd", UtcTimes.format(effective));
    return m;
  }

  static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
    return a.isAfter(b) ? a : b;
  }

  static void requireOne(int changed) {
    if (changed != 1) throw new IllegalStateException(
      "Locked quote write affected " + changed + " rows"
    );
  }

  private static ApiException baseline(String field, String message) {
    return ApiException.of(ErrorCode.QUOTE_VERSION_CONFLICT).field(
      FieldErrors.baselineMismatch(field, message)
    );
  }

  static ApiException expired(QuoteRow q) {
    return ApiException.of(ErrorCode.QUOTE_EXPIRED)
      .detail("quoteId", "" + q.getId())
      .detail("version", "" + q.getVersion());
  }
}
