package com.autocare.server.order.booking;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.common.identity.Role;
import com.autocare.server.availability.OccupiedRow;
import com.autocare.server.command.CommandExecutor;
import com.autocare.server.command.CommandOutcome;
import com.autocare.server.command.CommandResult;
import com.autocare.server.command.CommandSpec;
import com.autocare.server.order.BookingItemSnapshot;
import com.autocare.server.order.RulesSnapshot;
import com.autocare.server.order.lifecycle.OrderStateLogEntry;
import com.autocare.server.order.query.OrderDetail;
import com.autocare.server.user.UserEntity;
import com.autocare.server.user.UserMapper;
import com.autocare.server.vehicle.VehicleEntity;
import com.autocare.server.vehicle.VehicleMapper;
import com.autocare.server.web.UtcTimes;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 创建预约（G1-03）。**整个项目技术核心的第一片**：两个人同时约同一个技师的同一时段，
 * 数据库层面必须保证只有一个人成功。
 *
 * <p><b>锁内的事务内顺序照契约 §5.1 原文</b>（登记幂等由 {@link CommandExecutor} 在进 lambda 之前做完）：
 *
 * <pre>
 * 锁技师资源行 → occupiedStart 不得早于锁内 now → FOR SHARE 稳定读目录（serviceItemId 升序）
 * → 校验工作窗口 → 检查冲突 → 写订单快照与占用 → 写真实状态流水 → 保存成功结果 → 提交
 * </pre>
 *
 * <p>与契约原文的一处次序差异（如实登记，回报有说明）：「校验工作窗口」需要总工时 D，
 * 而 D 来自目录读取，所以实现把「FOR SHARE 读目录」提到「整段窗口校验」之前。
 * 两个校验都在同一把技师资源行锁内、错误码互不影响；只有当一个请求**同时**踩中两个错误时，
 * 返回的会是目录版本冲突而不是窗口违规。
 *
 * <p><b>锁的次序为什么不能动</b>：任何人想动这条占用，都得先握住技师资源行的行锁——
 * 就算此刻没有任何订单撞在这段时间上，这把锁也一定存在。READ COMMITTED 下“先读后写”
 * 之间的空窗正是靠它堵住的；管理员入口、扫描与恢复任务都不许绕过这条协议。
 *
 * <p><b>归属检查先于重放</b>（G1-02a 立下的样板）：「车是不是本人的」在 {@code execute} **之前**判
 * （否则会挡住合法重放），同时给机制传一个只查归属的 {@code ReplayVisibility}——
 * 前者是 §5.3 第 1 步，后者是第 2 步的复核，两件事不能混。
 */
@Service
public class OrderService {

  private static final Pattern ENTITY_ID = Pattern.compile(
    "^[1-9][0-9]{0,18}$"
  );
  private static final Pattern VERSION = Pattern.compile("^[1-9][0-9]{0,9}$");
  private static final int MAX_ITEMS = 50;
  private static final int ACCEPT_WINDOW_MINUTES =
    RulesSnapshot.DEFAULT.acceptWindowMinutes();

  private final OrderMapper orderMapper;
  private final VehicleMapper vehicleMapper;
  private final UserMapper userMapper;
  private final CommandExecutor commandExecutor;
  private final ObjectMapper objectMapper;

  public OrderService(
    OrderMapper orderMapper,
    VehicleMapper vehicleMapper,
    UserMapper userMapper,
    CommandExecutor commandExecutor,
    ObjectMapper objectMapper
  ) {
    this.orderMapper = orderMapper;
    this.vehicleMapper = vehicleMapper;
    this.userMapper = userMapper;
    this.commandExecutor = commandExecutor;
    this.objectMapper = objectMapper;
  }

  public CommandResult create(
    long actorId,
    CreateOrderRequest body,
    String key,
    String requestId
  ) {
    assertOwner(actorId);
    CreateOrderRequest.Parsed parsed = validate(body);
    // 「车是不是本人的」只查存在与归属，排在 execute 之前：不是/不存在 → 404，
    // 且这个 404 发生在重放检查之前是契约要求的（失败要 404 藏住存在性）。
    if (vehicleMapper.findOwned(parsed.vehicleId(), actorId) == null) {
      throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return commandExecutor.execute(
      CommandSpec.of("createOrder").orderLinked(),
      key,
      body,
      requestId,
      () -> createInLock(actorId, parsed, requestId),
      this::orderVisible
    );
  }

  private boolean orderVisible(long actorId, Long resourceId) {
    if (resourceId == null) {
      return false;
    }
    return orderMapper.countForOwner(resourceId, actorId) > 0;
  }

  // ------------------------------------------------------------------ 锁内的业务动作

  private CommandOutcome<OrderDetail> createInLock(
    long actorId,
    CreateOrderRequest.Parsed parsed,
    String requestId
  ) {
    // ① 锁技师资源行。行不存在或停用 → 404（错误表「技师不可见」）。
    OrderMapper.TechnicianLockRow technician = orderMapper.lockTechnician(
      parsed.technicianId()
    );
    if (technician == null) {
      throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    }
    // 管理员调整角色也遵循技师行在前、账号行在后的锁序。
    assertOwner(userMapper.lockIdentity(actorId));
    // ② 锁内 now：occupiedStart 不得早于它（错误表 WORK_WINDOW_VIOLATION 的第二个触发条件）。
    LocalDateTime nowUtc = orderMapper.dbNowUtc();

    // ③ FOR SHARE 稳定读目录（serviceItemId 升序），核停用与版本。
    List<Long> itemIds = parsed
      .items()
      .stream()
      .map(CreateOrderRequest.Parsed.Item::serviceItemId)
      .toList();
    List<CatalogQuoteRow> catalog = orderMapper.findCatalogForShare(itemIds);
    Map<Long, CatalogQuoteRow> byId = new HashMap<>();
    for (CatalogQuoteRow row : catalog) {
      byId.put(row.getId(), row);
    }
    List<BookingItemSnapshot> snapshots = new ArrayList<>();
    int totalMinutes = 0;
    for (CreateOrderRequest.Parsed.Item item : parsed.items()) {
      CatalogQuoteRow row = byId.get(item.serviceItemId());
      if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
        // 本片拍板：不存在与停用对预约意向都是「不可用」，落同一个 409
        throw ApiException.of(ErrorCode.SERVICE_ITEM_UNAVAILABLE);
      }
      if (!item.catalogVersion().equals(String.valueOf(row.getVersion()))) {
        throw ApiException.of(ErrorCode.CATALOG_VERSION_CONFLICT);
      }
      // D 是「计划工时」：单件工时 × 数量再求和（§2.4 lineMinutes 的定义），不是单件工时直接相加
      totalMinutes += row.getDurationMinutes() * item.quantity();
      snapshots.add(
        new BookingItemSnapshot(
          String.valueOf(row.getId()),
          row.getCode(),
          row.getName(),
          row.getPrice().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
          row.getCurrency(),
          item.quantity(),
          row.getDurationMinutes(),
          item.catalogVersion()
        )
      );
    }
    RulesSnapshot rules = RulesSnapshot.DEFAULT;

    // ④ 三种占用值（设计 §3.3）。全部转成 UTC 的 LocalDateTime 落库（库里 DATETIME(3) 就是 UTC）。
    int lead = technician.getCommuteMinutes() + rules.bufferBeforeMinutes();
    Instant occupiedStartUtc = parsed
      .serviceStartUtc()
      .minus(Duration.ofMinutes(lead));
    Instant bookingEndUtc = parsed
      .serviceStartUtc()
      .plus(
        Duration.ofMinutes((long) totalMinutes + rules.bufferAfterMinutes())
      );
    LocalDateTime occupiedStart = toUtc(occupiedStartUtc);
    LocalDateTime bookingEnd = toUtc(bookingEndUtc);
    LocalDateTime serviceStart = toUtc(parsed.serviceStartUtc());

    // ⑤ 整段占用必须落在技师工作窗口内；起点不得早于锁内 now。两者都归 WORK_WINDOW_VIOLATION。
    if (
      orderMapper.countWindowsCovering(
        parsed.technicianId(),
        occupiedStart,
        bookingEnd
      ) == 0 ||
      occupiedStart.isBefore(nowUtc)
    ) {
      throw ApiException.of(ErrorCode.WORK_WINDOW_VIOLATION);
    }

    // ⑥ 冲突检查在技师资源锁之后做当前锁定读：本查询在锁内执行，READ COMMITTED 不省协议。
    List<OccupiedRow> conflicts = orderMapper.findConflicts(
      parsed.technicianId(),
      occupiedStart,
      bookingEnd
    );
    if (!conflicts.isEmpty()) {
      // 不回传冲突方订单/车主——那等于泄露别人的预约
      throw ApiException.of(ErrorCode.SLOT_CONFLICT);
    }

    // ⑦ 写订单快照与占用 → 写真实状态流水 → 保存成功结果，与幂等登记同事务提交。
    // 与持久化的 DATETIME(3) 使用同一数据库时钟/精度，避免响应截断、落库四舍五入差 1ms。
    LocalDateTime createdAt = orderMapper.dbNowUtc();
    OrderInsertRow row = new OrderInsertRow();
    // 占位单号：去横线 UUID 恰好 32 字符，不超 VARCHAR(32)；插入后立刻回填正式单号
    row.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
    row.setOwnerId(actorId);
    row.setVehicleId(parsed.vehicleId());
    row.setTechnicianId(parsed.technicianId());
    row.setState("RESERVED");
    row.setRowVersion(1);
    row.setServiceStart(serviceStart);
    row.setOccupiedStart(occupiedStart);
    row.setCommittedEnd(
      toUtc(
        parsed
          .serviceStartUtc()
          .plus(
            Duration.ofMinutes((long) totalMinutes + rules.bufferAfterMinutes())
          )
      )
    );
    row.setEffectiveEnd(bookingEnd);
    row.setAcceptDeadline(createdAt.plusMinutes(ACCEPT_WINDOW_MINUTES));
    row.setBookingItemsJson(objectMapper.writeValueAsString(snapshots));
    row.setRulesSnapshotJson(objectMapper.writeValueAsString(rules));
    row.setAddressSnapshot(parsed.address());
    row.setRawInput(parsed.description());
    if (
      parsed.aiParseId() != null &&
      orderMapper.ownedBookingParse(parsed.aiParseId(), actorId) == null
    ) {
      throw ApiException.invalid(
        FieldErrors.notAvailable("/aiParseId", "草稿解析记录不存在或不可关联")
      );
    }
    row.setAiParseId(parsed.aiParseId());
    row.setMileageSnapshot(vehicleMileage(actorId, parsed.vehicleId()));
    row.setModelSnapshot(vehicleModel(actorId, parsed.vehicleId()));
    row.setPlateSnapshot(vehiclePlate(actorId, parsed.vehicleId()));
    row.setCreatedAt(createdAt);
    row.setUpdatedAt(createdAt);
    orderMapper.insertOrder(row);

    // 单号回填：AC + UTC 日期 + 三位序号（取主键）。主键唯一 → 单号必然唯一，并发不撞号。
    String orderNo =
      "AC" + yyyyUtc(createdAt) + String.format("%03d", row.getId());
    orderMapper.updateOrderNo(row.getId(), orderNo);
    orderMapper.insertCreateStateLog(
      row.getId(),
      actorId,
      requestId,
      createdAt
    );

    OrderDetail detail = new OrderDetail(
      String.valueOf(row.getId()),
      orderNo,
      "RESERVED",
      "1",
      String.valueOf(actorId),
      String.valueOf(parsed.vehicleId()),
      String.valueOf(parsed.technicianId()),
      UtcTimes.format(parsed.serviceStartUtc()),
      UtcTimes.format(occupiedStartUtc),
      UtcTimes.format(bookingEndUtc),
      UtcTimes.format(bookingEndUtc),
      null,
      null,
      List.of(),
      null,
      UtcTimes.format(row.getAcceptDeadline()),
      parsed.address(),
      vehicleModel(actorId, parsed.vehicleId()),
      vehiclePlate(actorId, parsed.vehicleId()),
      vehicleMileage(actorId, parsed.vehicleId()),
      List.copyOf(snapshots),
      rules,
      null,
      null,
      null,
      List.of(),
      List.of(
        new OrderStateLogEntry(
          null,
          "RESERVED",
          "CREATE_ORDER",
          String.valueOf(actorId),
          requestId,
          UtcTimes.format(createdAt)
        )
      ),
      UtcTimes.format(createdAt),
      UtcTimes.format(createdAt),
      java.util.List.of()
    );
    return CommandOutcome.created(
      "/api/v1/orders/" + row.getId(),
      detail,
      row.getId()
    );
  }

  // ------------------------------------------------------------------ 语法层校验（execute 之前）

  private CreateOrderRequest.Parsed validate(CreateOrderRequest body) {
    long vehicleId = requireEntityId(body, "vehicleId");
    long technicianId = requireEntityId(body, "technicianId");
    Instant serviceStartUtc = parseServiceStart(body);
    List<CreateOrderRequest.Parsed.Item> items = validateItems(body.getItems());

    if (!body.has("address")) {
      throw ApiException.invalid(FieldErrors.required("/address"));
    }
    if (body.getAddress() == null) {
      throw ApiException.invalid(FieldErrors.nullNotAllowed("/address"));
    }
    if (body.getAddress().isBlank() || body.getAddress().length() > 500) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/address", "地址长度必须为 1—500")
      );
    }
    String description = null;
    if (body.has("description")) {
      if (body.getDescription() == null) {
        throw ApiException.invalid(FieldErrors.nullNotAllowed("/description"));
      }
      if (body.getDescription().length() > 2000) {
        throw ApiException.invalid(
          FieldErrors.outOfRange("/description", "描述长度必须为 0—2000")
        );
      }
      description = body.getDescription();
    }
    Long aiParseId = null;
    if (body.getAiParseId() != null) {
      try {
        if (
          !ENTITY_ID.matcher(body.getAiParseId()).matches()
        ) throw new NumberFormatException();
        aiParseId = Long.parseLong(body.getAiParseId());
      } catch (NumberFormatException e) {
        throw ApiException.invalid(
          FieldErrors.invalidFormat("/aiParseId", "必须为无前导零的正整数")
        );
      }
    }
    return new CreateOrderRequest.Parsed(
      vehicleId,
      technicianId,
      serviceStartUtc,
      items,
      body.getAddress(),
      description,
      aiParseId
    );
  }

  private List<CreateOrderRequest.Parsed.Item> validateItems(
    List<CreateOrderRequest.BookingItemInput> items
  ) {
    if (items == null) {
      throw ApiException.invalid(FieldErrors.required("/items"));
    }
    if (items.isEmpty() || items.size() > MAX_ITEMS) {
      throw ApiException.invalid(
        FieldErrors.outOfRange("/items", "必须为 1—50 行")
      );
    }
    List<CreateOrderRequest.Parsed.Item> parsed = new ArrayList<>(items.size());
    Set<Long> seen = new HashSet<>();
    int index = 0;
    for (CreateOrderRequest.BookingItemInput item : items) {
      String path = "/items/" + index;
      if (item == null) {
        throw ApiException.invalid(FieldErrors.required(path));
      }
      if (item.getServiceItemId() == null) {
        throw ApiException.invalid(
          FieldErrors.required(path + "/serviceItemId")
        );
      }
      if (!ENTITY_ID.matcher(item.getServiceItemId()).matches()) {
        throw ApiException.invalid(
          FieldErrors.invalidFormat(
            path + "/serviceItemId",
            "必须为无前导零的十进制正整数"
          )
        );
      }
      long serviceItemId = Long.parseLong(item.getServiceItemId());
      if (item.getQuantity() == null) {
        throw ApiException.invalid(FieldErrors.required(path + "/quantity"));
      }
      if (item.getQuantity() < 1 || item.getQuantity() > 99) {
        throw ApiException.invalid(
          FieldErrors.outOfRange(path + "/quantity", "必须为 1—99")
        );
      }
      if (item.getCatalogVersion() == null) {
        throw ApiException.invalid(
          FieldErrors.required(path + "/catalogVersion")
        );
      }
      if (!VERSION.matcher(item.getCatalogVersion()).matches()) {
        throw ApiException.invalid(
          FieldErrors.invalidFormat(
            path + "/catalogVersion",
            "必须为无前导零的十进制正整数"
          )
        );
      }
      // 同一目录项重复：路径带行号，照契约样例 /items/1/serviceItemId
      if (!seen.add(serviceItemId)) {
        throw ApiException.invalid(
          FieldErrors.duplicate(
            path + "/serviceItemId",
            "同一服务项目不得重复提交"
          )
        );
      }
      parsed.add(
        new CreateOrderRequest.Parsed.Item(
          serviceItemId,
          item.getQuantity(),
          item.getCatalogVersion()
        )
      );
      index++;
    }
    // 快照数组顺序跟随目录读取顺序：serviceItemId 升序（契约 §5.1）
    parsed.sort(
      Comparator.comparingLong(CreateOrderRequest.Parsed.Item::serviceItemId)
    );
    return parsed;
  }

  private Instant parseServiceStart(CreateOrderRequest body) {
    if (!body.has("serviceStart")) {
      throw ApiException.invalid(FieldErrors.required("/serviceStart"));
    }
    if (body.getServiceStart() == null) {
      throw ApiException.invalid(FieldErrors.nullNotAllowed("/serviceStart"));
    }
    OffsetDateTime parsed;
    try {
      parsed = OffsetDateTime.parse(body.getServiceStart());
    } catch (DateTimeParseException e) {
      throw ApiException.invalid(
        FieldErrors.invalidFormat(
          "/serviceStart",
          "必须为带偏移的 RFC3339 时间"
        )
      );
    }
    // 契约：0—3 位小数。两条坑都要拦：ISO 解析能吃 9 位（.0000 纳秒为 0，按纳秒判拦不住），
    // 所以直接看文本里的小数位数
    if (body.getServiceStart().matches(".*\\.\\d{4,}.*")) {
      throw ApiException.invalid(
        FieldErrors.invalidFormat("/serviceStart", "小数最多 3 位")
      );
    }
    return parsed.toInstant();
  }

  private long requireEntityId(CreateOrderRequest body, String field) {
    if (!body.has(field)) {
      throw ApiException.invalid(FieldErrors.required("/" + field));
    }
    String value = switch (field) {
      case "vehicleId" -> body.getVehicleId();
      case "technicianId" -> body.getTechnicianId();
      default -> throw new IllegalStateException("未知字段 " + field);
    };
    if (value == null) {
      throw ApiException.invalid(FieldErrors.nullNotAllowed("/" + field));
    }
    if (!ENTITY_ID.matcher(value).matches()) {
      throw ApiException.invalid(
        FieldErrors.invalidFormat("/" + field, "必须为无前导零的十进制正整数")
      );
    }
    return Long.parseLong(value);
  }

  private void assertOwner(long actorId) {
    assertOwner(userMapper.findById(actorId));
  }

  private void assertOwner(UserEntity user) {
    if (user == null || !user.isEnabled()) {
      throw ApiException.of(ErrorCode.UNAUTHENTICATED);
    }
    if (user.getRole() != Role.OWNER) {
      throw ApiException.of(ErrorCode.FORBIDDEN);
    }
  }

  // ------------------------------------------------------------------ 车辆快照小工具

  private VehicleEntity ownedVehicle(long actorId, long vehicleId) {
    VehicleEntity vehicle = vehicleMapper.findOwned(vehicleId, actorId);
    if (vehicle == null) {
      // 锁内车辆被转移的极端情形：仍按 404 藏住（不存在与不是本人同形）
      throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return vehicle;
  }

  private String vehicleModel(long actorId, long vehicleId) {
    return ownedVehicle(actorId, vehicleId).getModelCode();
  }

  private String vehiclePlate(long actorId, long vehicleId) {
    return ownedVehicle(actorId, vehicleId).getPlateMasked();
  }

  private int vehicleMileage(long actorId, long vehicleId) {
    return ownedVehicle(actorId, vehicleId).getMileage();
  }

  // ------------------------------------------------------------------ 时间小工具

  private static LocalDateTime toUtc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static String yyyyUtc(LocalDateTime utc) {
    return utc.toLocalDate().toString().replace("-", "");
  }
}
