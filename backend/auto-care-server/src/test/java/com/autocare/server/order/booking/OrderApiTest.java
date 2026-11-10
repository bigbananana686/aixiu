package com.autocare.server.order.booking;

import com.autocare.server.web.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G1-03：创建预约。走完整过滤器链 ＋ 真实 MySQL ＋ 真实锁协议。
 *
 * <p>本册两条主线：
 * <ol>
 *   <li><b>写对</b>：201 的完整形状（快照、占用三值、规则快照、创建流水、接单截止）；</li>
 *   <li><b>挡对</b>：四种 409（SLOT_CONFLICT／WORK_WINDOW_VIOLATION／CATALOG_VERSION_CONFLICT／
 *       SERVICE_ITEM_UNAVAILABLE）各自只在自己该触发的条件下触发，404 藏住车辆与技师的存在性。</li>
 * </ol>
 *
 * <p><b>与 b2 册的关系</b>：那里证「提示」会把时段算对；这里证「提交」时锁内重新完整校验——
 * 两条同一套公式，但提示不算承诺（契约 §4.5／§3.4）。
 *
 * <p>测试会往 {@code t_service_order} 插伪造行验冲突（单号前缀 TSTAV-），本册自己创建的订单
 * 单号前缀 AC-，收尾全部清掉（先清状态流水再清订单，外键方向如此）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    private static final String DAY = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
    private static final String DATE_START = DAY + "T05:00:00.000Z";
    private static final String ADDRESS = "上海市浦东新区演示路 123 号";
    private static final String ITEM_1 = "[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}]";
    private static final String FIRST_REQUEST_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String SECOND_REQUEST_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long owner1;
    private long tech1;
    private long admin1;

    private final List<String> usedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        owner1 = userId("owner1");
        tech1 = userId("tech1");
        admin1 = userId("admin1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        // 先清状态流水（它有指向订单的外键），再清订单，最后清幂等记录
        jdbcTemplate.update("DELETE FROM t_order_state_log WHERE order_id IN "
                + "(SELECT id FROM t_service_order WHERE order_no LIKE 'AC%')");
        jdbcTemplate.update("DELETE FROM t_service_order WHERE order_no LIKE 'AC%'");
        jdbcTemplate.update("DELETE FROM t_service_order WHERE order_no LIKE 'TSTAV-%'");
        if (!usedKeys.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(usedKeys.size(), "?"));
            jdbcTemplate.update("DELETE FROM t_idempotency WHERE idempotency_key IN (" + placeholders + ")",
                    usedKeys.toArray());
        }
        usedKeys.clear();
    }

    // ------------------------------------------------------------------ 1. 正常路径

    /**
     * 201 的完整形状：三种占用值（occupied 04:15 = S − 30 − 15；committed/effective 06:00 = S + 45 + 15）、
     * 快照、规则快照、创建流水、接单截止——一单把 G1 后面所有片要消费的字段全部立起来。
     */
    @Test
    void createReturns201WithCompleteOrderDetail() throws Exception {
        MvcResult result = mockMvc.perform(create("1", "3", DATE_START, ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern("/api/v1/orders/\\d+")))
                .andExpect(jsonPath("$.data.state").value("RESERVED"))
                .andExpect(jsonPath("$.data.rowVersion").value("1"))
                .andExpect(jsonPath("$.data.orderNo").value(startsWith("AC")))
                .andExpect(jsonPath("$.data.ownerId").value("1"))
                .andExpect(jsonPath("$.data.occupiedStart").value(DAY + "T04:15:00.000Z"))
                .andExpect(jsonPath("$.data.committedEnd").value(DAY + "T06:00:00.000Z"))
                .andExpect(jsonPath("$.data.effectiveEnd").value(DAY + "T06:00:00.000Z"))
                .andExpect(jsonPath("$.data.acceptDeadline").isNotEmpty())
                .andExpect(jsonPath("$.data.bookingItems.length()").value(1))
                .andExpect(jsonPath("$.data.bookingItems[0].serviceItemId").value("1"))
                .andExpect(jsonPath("$.data.bookingItems[0].code").value("OIL_FILTER"))
                .andExpect(jsonPath("$.data.bookingItems[0].quantity").value(1))
                .andExpect(jsonPath("$.data.bookingItems[0].durationMinutes").value(45))
                .andExpect(jsonPath("$.data.bookingItems[0].catalogVersion").value("1"))
                .andExpect(jsonPath("$.data.bookingItems[0].unitPrice").value(matchesPattern("\\d+\\.\\d{2}")))
                .andExpect(jsonPath("$.data.rulesSnapshot.commuteMinutes").value(30))
                .andExpect(jsonPath("$.data.rulesSnapshot.bufferBeforeMinutes").value(15))
                .andExpect(jsonPath("$.data.rulesSnapshot.bufferAfterMinutes").value(15))
                .andExpect(jsonPath("$.data.rulesSnapshot.quoteConfirmWindowMinutes").value(10))
                .andExpect(jsonPath("$.data.rulesSnapshot.acceptWindowMinutes").value(15))
                .andExpect(jsonPath("$.data.approvedItems").isEmpty())
                .andExpect(jsonPath("$.data.approvedQuoteId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.stateLog.length()").value(1))
                .andExpect(jsonPath("$.data.stateLog[0].fromState").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.stateLog[0].toState").value("RESERVED"))
                .andExpect(jsonPath("$.data.stateLog[0].action").value("CREATE_ORDER"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        evidence("order-201-full-detail", result);

        long orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_service_order WHERE owner_id = ? AND state = 'RESERVED'",
                Long.class, owner1);
        assertThat(orders).as("库里真实落了一行 RESERVED").isEqualTo(1);
    }

    /**
     * 多项且乱序提交：快照按 serviceItemId 升序重排；工时按「单件 × 数量」求和——
     * 机油滤 ×2（45×2=90）＋ 空气滤 ×1（20）= D 110，committedEnd = 05:00 ＋ 110 ＋ 15 = <b>07:05</b>。
     * 写成「单件工时直接相加」（D=65 → 06:20）这条就红了。
     */
    @Test
    void multipleItemsAreSortedAscendingAndSummed() throws Exception {
        String items = "[{\"serviceItemId\":\"2\",\"quantity\":1,\"catalogVersion\":\"1\"},"
                + "{\"serviceItemId\":\"1\",\"quantity\":2,\"catalogVersion\":\"1\"}]";
        MvcResult result = mockMvc.perform(
                        create("1", "3", DATE_START, items, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookingItems.length()").value(2))
                .andExpect(jsonPath("$.data.bookingItems[0].serviceItemId").value("1"))
                .andExpect(jsonPath("$.data.bookingItems[1].serviceItemId").value("2"))
                .andExpect(jsonPath("$.data.committedEnd").value(DAY + "T07:05:00.000Z"))
                .andReturn();
        evidence("order-201-two-items-sorted-summed", result);
    }

    // ------------------------------------------------------------------ 2. 幂等

    @Test
    void replayReturnsOriginal201WithoutNewRow() throws Exception {
        String key = newKey();
        mockMvc.perform(create("1", "3", DATE_START, ITEM_1, key, FIRST_REQUEST_ID))
                .andExpect(status().isCreated());

        MvcResult replay = mockMvc.perform(create("1", "3", DATE_START, ITEM_1, key, SECOND_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("Original-Request-Id", FIRST_REQUEST_ID))
                .andReturn();
        evidence("order-201-replay", replay);

        Long orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_service_order WHERE owner_id = ?", Long.class, owner1);
        assertThat(orders).as("重放不产生第二条订单").isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIs409() throws Exception {
        String key = newKey();
        mockMvc.perform(create("1", "3", DATE_START, ITEM_1, key, FIRST_REQUEST_ID))
                .andExpect(status().isCreated());

        evidence("order-409-same-key-different-body", mockMvc.perform(
                        create("1", "3", DAY + "T06:00:00.000Z", ITEM_1, key, SECOND_REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andReturn());
    }

    @Test
    void missingOrMalformedKeyRejected() throws Exception {
        evidence("order-400-missing-key", mockMvc.perform(post("/api/v1/orders")
                        .with(authentication(jwt(owner1, "ROLE_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "3", DATE_START, ITEM_1, ADDRESS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/headers/Idempotency-Key"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                .andReturn());

        evidence("order-400-malformed-key", mockMvc.perform(
                        create("1", "3", DATE_START, ITEM_1, "short", FIRST_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());
    }

    // ------------------------------------------------------------------ 3. 归属与存在性（404 藏住存在性）

    @Test
    void vehicleNotOwnedOrMissingIs404() throws Exception {
        evidence("order-404-vehicle-not-mine", mockMvc.perform(
                        create("2", "3", DATE_START, ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andReturn());

        MvcResult missing = mockMvc.perform(
                        create("999999", "3", DATE_START, ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andReturn();
        evidence("order-404-vehicle-missing", missing);

        assertThat(raw(missing)).as("404 不回 data").doesNotContain("\"data\"");
    }

    @Test
    void unknownTechnicianIs404() throws Exception {
        evidence("order-404-technician-invisible", mockMvc.perform(
                        create("1", "999999", DATE_START, ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andReturn());
    }

    // ------------------------------------------------------------------ 4. 锁内校验：窗口与冲突

    /** 查询日没有任何窗口（V901 从 11-01 开始）→ 整段占用不在窗口内 → 409 WORK_WINDOW_VIOLATION。 */
    @Test
    void outsideAnyWindowIs409WorkWindowViolation() throws Exception {
        evidence("order-409-outside-window", mockMvc.perform(
                        create("1", "3", "2026-10-31T05:00:00.000Z", ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WORK_WINDOW_VIOLATION"))
                .andReturn());

        Long orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_service_order WHERE owner_id = ?", Long.class, owner1);
        assertThat(orders).as("失败的命令不落订单").isZero();
    }

    /**
     * 冲突与端点相接：先伪造一条占用 [04:15, 06:00) 的「别人已约」，
     * 05:00 下单 → 409 SLOT_CONFLICT；06:45 下单（占用 [06:00, 07:45)，与前者端点相接）→ 201。
     * 提示查询（b2 册）与提交校验是两道独立关卡，但用的是同一套半开区间公式。
     */
    @Test
    void overlappingReservationConflictsButTouchingOneSucceeds() throws Exception {
        insertReservation("TSTAV-11", tech1,
                DAY + " 04:15:00", DAY + " 06:00:00", DAY + " 06:00:00", DAY + " 05:00:00");

        evidence("order-409-slot-conflict", mockMvc.perform(
                        create("1", "3", DATE_START, ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_CONFLICT"))
                .andReturn());

        // 端点相接：06:45 起点会让占用从 06:00 开始——与上一单的结束点相接，允许
        MvcResult touching = mockMvc.perform(
                        create("1", "3", DAY + "T06:45:00.000Z", ITEM_1, newKey(), SECOND_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.occupiedStart").value(DAY + "T06:00:00.000Z"))
                .andReturn();
        evidence("order-201-touching-occupancy-allowed", touching);

        assertThat(raw(touching)).as("冲突时不泄露对方订单信息")
                .doesNotContain("TSTAV").doesNotContain("1002");
    }

    // ------------------------------------------------------------------ 5. 目录版本与可用性

    @Test
    void catalogVersionMismatchIs409() throws Exception {
        String stale = "[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"9\"}]";
        evidence("order-409-catalog-version", mockMvc.perform(
                        create("1", "3", DATE_START, stale, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATALOG_VERSION_CONFLICT"))
                .andReturn());
    }

    @Test
    void unknownOrDisabledItemIs409ServiceItemUnavailable() throws Exception {
        evidence("order-409-item-unknown", mockMvc.perform(
                        create("1", "3", DATE_START,
                                "[{\"serviceItemId\":\"999\",\"quantity\":1,\"catalogVersion\":\"1\"}]",
                                newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SERVICE_ITEM_UNAVAILABLE"))
                .andReturn());

        // BRAKE_PAD：种子里唯一的停用项——停用项不能成为预约意向
        evidence("order-409-item-disabled", mockMvc.perform(
                        create("1", "3", DATE_START,
                                "[{\"serviceItemId\":\"5\",\"quantity\":1,\"catalogVersion\":\"1\"}]",
                                newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SERVICE_ITEM_UNAVAILABLE"))
                .andReturn());
    }

    // ------------------------------------------------------------------ 6. 请求体校验

    @Test
    void duplicateItemsRejectedWithRowPath() throws Exception {
        String duplicated = "[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"},"
                + "{\"serviceItemId\":\"1\",\"quantity\":2,\"catalogVersion\":\"1\"}]";
        evidence("order-400-duplicate-item", mockMvc.perform(
                        create("1", "3", DATE_START, duplicated, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/items/1/serviceItemId"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("DUPLICATE"))
                .andReturn());
    }

    @Test
    void quantityRulesEnforced() throws Exception {
        evidence("order-400-quantity-zero", mockMvc.perform(
                        create("1", "3", DATE_START,
                                "[{\"serviceItemId\":\"1\",\"quantity\":0,\"catalogVersion\":\"1\"}]",
                                newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/items/0/quantity"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andReturn());

        evidence("order-400-quantity-missing", mockMvc.perform(
                        create("1", "3", DATE_START,
                                "[{\"serviceItemId\":\"1\",\"catalogVersion\":\"1\"}]",
                                newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/items/0/quantity"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                .andReturn());
    }

    @Test
    void addressRulesEnforced() throws Exception {
        evidence("order-400-address-missing", mockMvc.perform(post("/api/v1/orders")
                        .with(authentication(jwt(owner1, "ROLE_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", newKey())
                        .content("{\"vehicleId\":\"1\",\"technicianId\":\"3\","
                                + "\"serviceStart\":\"" + DATE_START + "\",\"items\":" + ITEM_1 + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/address"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                .andReturn());

        // 显式 null：契约「省略即不提供」——null 不是省略，单独报 NULL_NOT_ALLOWED
        String nullAddress = "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" + DATE_START
                + "\",\"items\":" + ITEM_1 + ",\"address\":null}";
        evidence("order-400-address-null", mockMvc.perform(post("/api/v1/orders")
                        .with(authentication(jwt(owner1, "ROLE_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", newKey())
                        .content(nullAddress))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/address"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NULL_NOT_ALLOWED"))
                .andReturn());
    }

    @Test
    void ownerIdInBodyIsUnknownField() throws Exception {
        evidence("order-400-ownerId-in-body", mockMvc.perform(post("/api/v1/orders")
                        .with(authentication(jwt(owner1, "ROLE_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", newKey())
                        .content("{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"ownerId\":\"2\","
                                + "\"serviceStart\":\"" + DATE_START + "\",\"items\":" + ITEM_1
                                + ",\"address\":\"" + ADDRESS + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/ownerId"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("UNKNOWN_FIELD"))
                .andReturn());
    }

    @Test
    void malformedServiceStartRejected() throws Exception {
        evidence("order-400-start-without-offset", mockMvc.perform(
                        create("1", "3", DAY + "T05:00:00", ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/serviceStart"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());

        evidence("order-400-start-4-decimals", mockMvc.perform(
                        create("1", "3", DAY + "T05:00:00.0000Z", ITEM_1, newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());
    }

    // ------------------------------------------------------------------ 7. 角色与认证

    @Test
    void onlyOwnerCanCreateOrder() throws Exception {
        evidence("order-403-tech", mockMvc.perform(
                        create("1", "3", DATE_START, ITEM_1, newKey(), FIRST_REQUEST_ID)
                                .with(authentication(jwt(tech1, "ROLE_TECH"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andReturn());

        evidence("order-403-admin", mockMvc.perform(
                        create("1", "3", DATE_START, ITEM_1, newKey(), FIRST_REQUEST_ID)
                                .with(authentication(jwt(admin1, "ROLE_ADMIN"))))
                .andExpect(status().isForbidden())
                .andReturn());

        // 这一条要「不带任何身份」，所以不走 create 助手（它会默认挂 owner1）
        evidence("order-401-unauthenticated", mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", newKey())
                        .content(body("1", "3", DATE_START, ITEM_1, ADDRESS)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andReturn());
    }

    // ------------------------------------------------------------------ 小工具

    private MockHttpServletRequestBuilder create(String vehicleId, String technicianId, String serviceStart,
                                                 String items, String key, String requestId) {
        // 默认挂 owner1：绝大多数用例都是车主下单；要换身份的在调用处再 .with(...) 覆盖
        MockHttpServletRequestBuilder builder = post("/api/v1/orders")
                .with(authentication(jwt(owner1, "ROLE_OWNER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(vehicleId, technicianId, serviceStart, items, ADDRESS));
        if (key != null) {
            builder = builder.header("Idempotency-Key", key);
        }
        if (requestId != null) {
            builder = builder.header(RequestIdFilter.HEADER_NAME, requestId);
        }
        return builder;
    }

    private static String body(String vehicleId, String technicianId, String serviceStart,
                               String items, String address) {
        return "{\"vehicleId\":\"" + vehicleId + "\",\"technicianId\":\"" + technicianId
                + "\",\"serviceStart\":\"" + serviceStart + "\",\"items\":" + items
                + ",\"address\":\"" + address + "\"}";
    }

    /**
     * 伪造一条「别人已约」的最小订单行（同 b2 册的造数方式，单号前缀 TSTAV-，收尾清掉）。
     * 直接指定占用区间，不走 createOrder——我们要的是可控的占用，不是又一次 create 的测试。
     */
    private void insertReservation(String orderNo, long technicianId, String occupiedStart,
                                   String committedEnd, String effectiveEnd, String serviceStart) {
        jdbcTemplate.update("""
                        INSERT INTO t_service_order (order_no, owner_id, vehicle_id, technician_id, state,
                            service_start, occupied_start, committed_end, effective_end, reservation_active,
                            accept_deadline, booking_items_json, rules_snapshot_json, address_snapshot,
                            mileage_snapshot, model_snapshot, plate_snapshot, created_at, updated_at)
                        VALUES (?, 2, 2, ?, 'RESERVED', ?, ?, ?, ?, 1, '2026-11-01 00:00:00',
                            '[{"service_item_id":1,"quantity":1,"catalog_version":"1"}]',
                            '{"commute_minutes":30,"buffer_before_minutes":15,"buffer_after_minutes":15,
                              "quote_confirm_window_minutes":10,"accept_window_minutes":15}',
                            '测试地址', 100, 'CAMRY_2021', '沪B***90', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                        """,
                orderNo, technicianId, serviceStart, occupiedStart, committedEnd, effectiveEnd);
    }

    private JwtAuthenticationToken jwt(long userId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    private String newKey() {
        String key = UUID.randomUUID().toString();
        usedKeys.add(key);
        return key;
    }

    private long userId(String loginName) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_user WHERE login_name = ?", Long.class, loginName);
        return id == null ? 0 : id;
    }

    private String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void evidence(String what, MvcResult result) throws Exception {
        MockHttpServletRequest req = result.getRequest();
        StringBuilder sb = new StringBuilder();
        sb.append("req=").append(req.getMethod()).append(' ').append(req.getRequestURI());
        sb.append(" body=").append(req.getContentAsString());
        sb.append(" | resp status=").append(result.getResponse().getStatus());
        sb.append(" headers={");
        List<String> names = new ArrayList<>(result.getResponse().getHeaderNames());
        Collections.sort(names);
        for (String name : names) {
            sb.append(name).append('=').append(result.getResponse().getHeaders(name)).append("; ");
        }
        sb.append("} body=").append(raw(result));
        System.out.println("[G1-03] " + what + " = " + ascii(sb.toString()));
    }

    private static String ascii(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c >= 32 && c < 127) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }
}
