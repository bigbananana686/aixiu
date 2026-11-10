package com.autocare.server.order.query;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.common.identity.Role;
import com.autocare.server.order.BookingItemSnapshot;
import com.autocare.server.order.OrderInputs;
import com.autocare.server.order.OrderRow;
import com.autocare.server.order.RulesSnapshot;
import com.autocare.server.order.lifecycle.OrderLifecycleMapper;
import com.autocare.server.order.lifecycle.OrderLifecycleService;
import com.autocare.server.order.lifecycle.OrderOperationEntry;
import com.autocare.server.order.lifecycle.OrderStateLogEntry;
import com.autocare.server.order.quote.QuoteMapper;
import com.autocare.server.order.quote.QuoteRow;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.ListInputs;
import com.autocare.server.web.UtcTimes;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderQueryService {

  private static final Set<String> STATES = Set.of(
    "RESERVED",
    "ACCEPTED",
    "EN_ROUTE",
    "ARRIVED",
    "QUOTE_PENDING",
    "READY_FOR_SERVICE",
    "SERVICING",
    "COMPLETED",
    "CLOSED",
    "CANCELLED"
  );
  private static final Set<String> QUERIES = Set.of(
    "page",
    "pageSize",
    "state",
    "from",
    "to"
  );
  private final OrderLifecycleMapper mapper;
  private final OrderLifecycleService lifecycle;
  private final ObjectMapper json;
  private final QuoteMapper quotes;
  private final AdminAccess admin;
  private final com.autocare.server.order.exception.OrderExceptionMapper exceptions;

  public OrderQueryService(
    OrderLifecycleMapper mapper,
    OrderLifecycleService lifecycle,
    ObjectMapper json,
    QuoteMapper quotes,
    com.autocare.server.order.exception.OrderExceptionMapper exceptions,
    AdminAccess admin
  ) {
    this.mapper = mapper;
    this.lifecycle = lifecycle;
    this.json = json;
    this.quotes = quotes;
    this.exceptions = exceptions;
    this.admin = admin;
  }

  // GET 用一致性快照组装主行和已提交流水；不锁资源、不读写幂等。
  // 订单写事务仍沿用 RC。此只读事务用 RR 避免两次 SELECT 跨过并发提交。
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OrderDetail detail(long actor, String pathId) {
    Role role = lifecycle.currentRole(actor, null);
    OrderRow r = mapper.find(OrderInputs.id(pathId));
    OrderLifecycleService.requireVisible(r, actor, role);
    return detailView(r, false);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OrderDetail detailForAdmin(long actor, String pathId) {
    admin.require(actor);
    OrderRow row = mapper.find(OrderInputs.id(pathId));
    if (row == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    return detailView(row, true);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OrderListResult listForAdmin(long actor, MultiValueMap<String, String> query) {
    admin.require(actor);
    if (query.containsKey("enabled")) throw ApiException.invalid(FieldErrors.notAllowed("/enabled", "不接受的查询字段"));
    ListInputs page = ListInputs.parse(query);
    return new OrderListResult(
      mapper.listForAdmin(page.pageSize(), page.offset()).stream().map(this::summary).toList(),
      page.page(), page.pageSize(), mapper.countForAdmin()
    );
  }

  private OrderDetail detailView(OrderRow r, boolean redact) {
    QuoteRow approved =
      r.getApprovedQuoteId() == null
        ? null
        : quotes.find(r.getId(), r.getApprovedQuoteId());
    List<OrderStateLogEntry> logs = mapper
      .stateLog(r.getId())
      .stream()
      .map(e ->
        new OrderStateLogEntry(
          e.fromState(),
          e.toState(),
          e.action(),
          e.actorId(),
          e.requestId(),
          logTime(e.createdAt())
        )
      )
      .toList();
    List<OrderOperationEntry> operations = redact ? List.of() : mapper
      .operations(r.getId())
      .stream()
      .map(e ->
        new OrderOperationEntry(
          e.operationId(),
          e.action(),
          e.actorId(),
          e.quoteId(),
          e.payloadSummary(),
          e.requestId(),
          logTime(e.createdAt())
        )
      )
      .toList();
    return new OrderDetail(
      String.valueOf(r.getId()),
      r.getOrderNo(),
      r.getState(),
      String.valueOf(r.getRowVersion()),
      String.valueOf(r.getOwnerId()),
      String.valueOf(r.getVehicleId()),
      String.valueOf(r.getTechnicianId()),
      UtcTimes.format(r.getServiceStart()),
      UtcTimes.format(r.getOccupiedStart()),
      UtcTimes.format(r.getCommittedEnd()),
      UtcTimes.format(r.getEffectiveEnd()),
      OrderLifecycleService.nullableId(r.getApprovedQuoteId()),
      approved == null ? null : String.valueOf(approved.getVersion()),
      approved == null ? List.of() : quotes.items(approved.getId()),
      OrderLifecycleService.nullableId(r.getPendingQuoteId()),
      UtcTimes.format(r.getAcceptDeadline()),
      redact ? null : r.getAddressSnapshot(),
      r.getModelSnapshot(),
      r.getPlateSnapshot(),
      r.getMileageSnapshot(),
      json.readValue(
        r.getBookingItemsJson(),
        new TypeReference<List<BookingItemSnapshot>>() {}
      ),
      json.readValue(r.getRulesSnapshotJson(), RulesSnapshot.class),
      UtcTimes.format(r.getActualStart()),
      UtcTimes.format(r.getActualFinish()),
      redact ? null : r.getServiceResult(),
      operations,
      logs,
      UtcTimes.format(r.getCreatedAt()),
      UtcTimes.format(r.getUpdatedAt()),
      exceptions
        .forOrder(r.getId())
        .stream()
        .map(com.autocare.server.order.exception.OrderExceptionRow::view)
        .toList()
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OrderListResult list(long actor, MultiValueMap<String, String> query) {
    Role role = lifecycle.currentRole(actor, null);
    for (String name : query.keySet()) {
      if (!QUERIES.contains(name)) {
        if (Set.of("ownerId", "technicianId").contains(name)) {
          throw ApiException.invalid(
            FieldErrors.notAllowed("/" + name, "不接受身份筛选字段")
          );
        }
        throw ApiException.invalid(
          FieldErrors.unknownField("/" + name, "不接受的查询字段")
        );
      }
      if (query.get(name).size() != 1) throw ApiException.invalid(
        FieldErrors.duplicate("/" + name, "查询参数不可重复")
      );
    }
    int page = page(query.getFirst("page"), "page", 1, 10000);
    int size = page(query.getFirst("pageSize"), "pageSize", 20, 100);
    List<String> states = null;
    if (query.containsKey("state")) {
      states = Arrays.asList(query.getFirst("state").split(",", -1));
      if (states.stream().anyMatch(s -> !STATES.contains(s))) {
        throw ApiException.invalid(
          FieldErrors.invalidFormat("/state", "必须为逗号分隔的有效订单状态")
        );
      }
    }
    LocalDateTime from = time(query.getFirst("from"), "from");
    LocalDateTime to = time(query.getFirst("to"), "to");
    if (
      from != null &&
      to != null &&
      (!from.isBefore(to) ||
        Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
    ) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/to", "from 必须早于 to，跨度不超过 31 天")
      );
    }
    List<OrderSummary> rows = mapper
      .list(
        actor,
        role == Role.OWNER,
        states,
        from,
        to,
        size,
        (page - 1) * size
      )
      .stream()
      .map(this::summary)
      .toList();
    return new OrderListResult(
      rows,
      page,
      size,
      mapper.count(actor, role == Role.OWNER, states, from, to)
    );
  }

  private OrderSummary summary(OrderRow r) {
    return new OrderSummary(
      String.valueOf(r.getId()),
      r.getOrderNo(),
      r.getState(),
      String.valueOf(r.getRowVersion()),
      String.valueOf(r.getOwnerId()),
      String.valueOf(r.getVehicleId()),
      String.valueOf(r.getTechnicianId()),
      UtcTimes.format(r.getServiceStart()),
      UtcTimes.format(r.getCommittedEnd()),
      UtcTimes.format(r.getEffectiveEnd()),
      OrderLifecycleService.nullableId(r.getApprovedQuoteId()),
      OrderLifecycleService.nullableId(r.getPendingQuoteId()),
      UtcTimes.format(r.getAcceptDeadline()),
      UtcTimes.format(r.getCreatedAt()),
      UtcTimes.format(r.getUpdatedAt())
    );
  }

  private static String logTime(String time) {
    return UtcTimes.format(LocalDateTime.parse(time));
  }

  private static int page(String value, String field, int fallback, int max) {
    if (value == null) return fallback;
    try {
      if (!value.matches("[1-9][0-9]{0,8}")) throw new NumberFormatException();
      int parsed = Integer.parseInt(value);
      if (parsed > max) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException e) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/" + field, "分页参数超出范围")
      );
    }
  }

  private static LocalDateTime time(String value, String field) {
    if (value == null) return null;
    try {
      if (
        !value.matches(
          "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?(Z|[+-]\\d{2}:\\d{2})"
        )
      ) throw new DateTimeException("format");
      LocalDateTime result = LocalDateTime.ofInstant(
        OffsetDateTime.parse(value).toInstant(),
        ZoneOffset.UTC
      );
      if (
        result.getYear() < 2000 || result.getYear() > 2099
      ) throw new DateTimeException("range");
      return result;
    } catch (DateTimeException e) {
      throw ApiException.invalid(
        FieldErrors.invalidFormat(
          "/" + field,
          "必须为有效的带偏移 RFC3339 时间"
        )
      );
    }
  }
}
