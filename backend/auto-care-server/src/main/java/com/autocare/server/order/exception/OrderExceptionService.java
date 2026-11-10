package com.autocare.server.order.exception;

import com.autocare.common.error.*;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderExceptionService {

  private final OrderExceptionMapper mapper;
  private final OrderMapper clock;
  private final AdminAccess admins;
  private final ObjectMapper json;
  private final TransactionTemplate independent;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(
    OrderExceptionService.class
  );

  public OrderExceptionService(
    OrderExceptionMapper mapper,
    OrderMapper clock,
    AdminAccess admins,
    ObjectMapper json,
    PlatformTransactionManager manager
  ) {
    this.mapper = mapper;
    this.clock = clock;
    this.admins = admins;
    this.json = json;
    independent = new TransactionTemplate(manager);
    independent.setPropagationBehavior(
      TransactionDefinition.PROPAGATION_REQUIRES_NEW
    );
  }

  // 调用者已退出失败资源事务。这里只保存最小异常，不保存失败命令或状态流水。
  public boolean deferral(long order, String request, LocalDateTime target) {
    try {
      independent.executeWithoutResult(s ->
        mapper.insert(
          new OrderExceptionRow(
            0,
            "DEFERRAL_SLOT_CONFLICT",
            order,
            request,
            target,
            null,
            null,
            null,
            clock.dbNowUtc(),
            false
          )
        )
      );
      return true;
    } catch (org.springframework.dao.DataAccessException e) {
      log.error(
        "人工异常登记失败 orderId={} requestId={} exception={}",
        order,
        request,
        e.getClass().getSimpleName()
      );
      return false;
    }
  }

  public Map<String, Object> completion(
    long order,
    String request,
    LocalDateTime committed,
    LocalDateTime finish,
    LocalDateTime now
  ) {
    if (!finish.isAfter(committed)) return null;
    // 向上取整保留不足一分钟的真实延误；DDL限10080，超长延误保留时间并以该上限展示分钟数。
    long millis = Duration.between(committed, finish).toMillis();
    int minutes = (int) Math.min(10080, (millis + 59999) / 60000);
    mapper.insert(
      new OrderExceptionRow(
        0,
        "OVER_PLAN_COMPLETION",
        order,
        request,
        null,
        committed,
        finish,
        minutes,
        now,
        false
      )
    );
    var r = mapper.find(order, request, "OVER_PLAN_COMPLETION");
    return Map.of(
      "overPlanMinutes",
      minutes,
      "exceptionId",
      "" + r.id(),
      "recorded",
      true
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(long actor, MultiValueMap<String, String> q) {
    admins.require(actor);
    for (var e : q.entrySet()) {
      if (
        !Set.of(
          "page",
          "pageSize",
          "type",
          "orderId",
          "resolved",
          "from",
          "to"
        ).contains(e.getKey())
      ) throw ApiException.invalid(
        FieldErrors.unknownField("/" + e.getKey(), "未知字段")
      );
      if (e.getValue().size() != 1) throw ApiException.invalid(
        FieldErrors.duplicate("/" + e.getKey(), "参数不可重复")
      );
    }
    var paging = new org.springframework.util.LinkedMultiValueMap<
      String,
      String
    >();
    for (String k : List.of("page", "pageSize"))
      if (q.containsKey(k)) paging.add(k, q.getFirst(k));
    var p = ListInputs.parse(paging);
    Long order = q.containsKey("orderId")
      ? InputValues.id(q.getFirst("orderId"), "orderId")
      : null;
    Boolean resolved = null;
    if (q.containsKey("resolved")) {
      String v = q.getFirst("resolved");
      if (!Set.of("true", "false").contains(v)) throw ApiException.invalid(
        FieldErrors.invalidFormat("/resolved", "必须为true或false")
      );
      resolved = Boolean.valueOf(v);
    }
    List<String> types = null;
    if (q.containsKey("type")) {
      types = Arrays.asList(q.getFirst("type").split(",", -1));
      if (
        types
          .stream()
          .anyMatch(
            t ->
              !Set.of(
                "DEFERRAL_SLOT_CONFLICT",
                "OVER_PLAN_COMPLETION"
              ).contains(t)
          )
      ) throw ApiException.invalid(
        FieldErrors.outOfRange("/type", "异常类型非法")
      );
    }
    LocalDateTime now = clock.dbNowUtc();
    var node = json.valueToTree(q.toSingleValueMap());
    LocalDateTime to = q.containsKey("to") ? InputValues.time(node, "to") : now;
    LocalDateTime from = q.containsKey("from")
      ? InputValues.time(node, "from")
      : to.minusDays(7);
    if (
      !from.isBefore(to) ||
      Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
    ) throw ApiException.invalid(
      FieldErrors.outOfRange("/to", "from必须早于to且跨度不超过31天")
    );
    return Map.of(
      "items",
      mapper
        .list(order, resolved, types, from, to, p.pageSize(), p.offset())
        .stream()
        .map(OrderExceptionRow::view)
        .toList(),
      "page",
      p.page(),
      "pageSize",
      p.pageSize(),
      "total",
      mapper.count(order, resolved, types, from, to)
    );
  }
}
