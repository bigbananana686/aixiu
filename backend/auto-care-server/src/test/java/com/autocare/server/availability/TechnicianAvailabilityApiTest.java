package com.autocare.server.availability;

import com.autocare.server.web.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G1-02b2：技师可用时段（提示查询），走完整过滤器链，连真实 MySQL。
 *
 * <p><b>这条接口是 G1 第一段「要算数」的逻辑</b>，所以本册的重心不是状态码（那些在车辆册都验过套路了），
 * 而是<b>区间算得对不对</b>：
 * <ol>
 *   <li>无占用时：窗口两端各让出「通勤＋前缓冲」（30＋15）与「工时＋后缓冲」（45＋15），
 *       剩下的闭区间就是可选起点（如 {@code [01:45, 09:00]}）；</li>
 *   <li>有一条占用 {@code [O, E)} 时：被挡掉的起点是<b>开区间</b> {@code (O−tail, E+lead)}，
 *       整段候选被切成两段；</li>
 *   <li><b>端点相接不算冲突</b>（设计 §3.2 半开区间）：占用结束在 01:45，起点 02:30
 *       （= 01:45 ＋ 45 分钟通勤缓冲）必须仍然可选——这是「开区间减法」最容易写错的地方。</li>
 * </ol>
 *
 * <p><b>日期取 UTC 次日（隔离库 V901 从运行日生成工作窗口）</b>：窗口是 V901 刷出来的 2026-11-01—11-07。这册用 11-08 之后的日期
 * 会开始失败——那不是 bug，是「种子窗口该再刷新了」的提醒（照 V901 复制一个 V902）。
 *
 * <p><b>测试里会往 {@code t_service_order} 插最小订单行</b>（只填 NOT NULL 列，见
 * {@link #insertReservation}），跑完按 {@code order_no} 前缀清干净。种子里没有任何订单，
 * 不造数据就没法验「扣预约切段」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TechnicianAvailabilityApiTest {

    /** V901 种子窗口覆盖的第一天（UTC 01:00—10:00，即 Asia/Shanghai 09:00—18:00）。 */
    private static final String DATE = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
    /** 窗口区间之外的第一天：当天没有任何窗口，用来验「无解」的形状。 */
    private static final String DATE_WITHOUT_WINDOWS = java.time.LocalDate.parse(DATE).plusDays(7).toString();

    private long owner1;
    private long tech1;
    private long admin1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        owner1 = userId("owner1");
        tech1 = userId("tech1");
        admin1 = userId("admin1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        // 伪造的订单只进测试：按本册专用的单号前缀清，不碰任何真实/种子行
        jdbcTemplate.update("DELETE FROM t_service_order WHERE order_no LIKE 'TSTAV-%'");
    }

    // ------------------------------------------------------------------ 1. 正常路径与区间形状

    /**
     * 无占用：两项约束各让出一截——窗口头让出「通勤 30 ＋ 前缓冲 15」，窗口尾让出「工时 45 ＋ 后缓冲 15」
     * ——剩下 {@code [01:45, 08:15]}。两个技师窗口完全重合（V900/V901 刻意如此），区间也应完全相同。
     */
    @Test
    void ownerGetsBothTechniciansAscendingWithBufferedRanges() throws Exception {
        MvcResult result = mockMvc.perform(get(DATE, "1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(DATE))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.totalMinutes").value(45))
                .andExpect(jsonPath("$.data.technicians.length()").value(2))
                .andExpect(jsonPath("$.data.technicians[0].technicianId").value("3"))
                .andExpect(jsonPath("$.data.technicians[1].technicianId").value("4"))
                .andExpect(jsonPath("$.data.technicians[0].displayName").isNotEmpty())
                .andReturn();
        evidence("avail-200-both-techs-default-window", result);

        for (int i = 0; i < 2; i++) {
            assertThat(readRanges(result, i)).as("technicians[" + i + "] 的可选区间")
                    .containsExactly(new String[]{DATE + "T01:45:00.000Z", DATE + "T09:00:00.000Z"});
        }
    }

    /** 多项求和：{@code totalMinutes} 相加，上界按总工时＋后缓冲收缩，下界不受影响。 */
    @Test
    void multipleItemsSumIntoTotalMinutesAndShrinkUpperBound() throws Exception {
        MvcResult result = mockMvc.perform(get(DATE, "1,2", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalMinutes").value(65))
                .andReturn();
        evidence("avail-200-two-items-sum-65", result);

        assertThat(readRanges(result, 0))
                .containsExactly(new String[]{DATE + "T01:45:00.000Z", DATE + "T08:40:00.000Z"});
    }

    /** 带了 {@code technicianId} 就只回这一个技师——但仍是提示查询的形状。 */
    @Test
    void technicianIdFilterReturnsOnlyThatTechnician() throws Exception {
        MvcResult result = mockMvc.perform(get(DATE, "1", "3").with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.technicians.length()").value(1))
                .andExpect(jsonPath("$.data.technicians[0].technicianId").value("3"))
                .andReturn();
        evidence("avail-200-filtered-by-technicianId", result);
    }

    /**
     * 不存在的 {@code technicianId} → <b>200 ＋ 空数组</b>，不是 404（契约 §4.5：这是提示查询，
     * 不是对象读取——「查不到」本身就是有效答案）。
     */
    @Test
    void unknownTechnicianIdReturns200WithEmptyArray() throws Exception {
        MvcResult result = mockMvc.perform(
                        get(DATE, "1", "999999").with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.technicians").isEmpty())
                .andReturn();
        evidence("avail-200-unknown-technician-empty-array", result);
    }

    /** 查询日没有任何窗口 → 两个技师都在、区间为空（本片拍板项 1：留行给空区间，不整行省略）。 */
    @Test
    void dateWithoutWindowsYieldsTechniciansWithEmptyRanges() throws Exception {
        MvcResult result = mockMvc.perform(
                        get(DATE_WITHOUT_WINDOWS, "1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.technicians.length()").value(2))
                .andExpect(jsonPath("$.data.technicians[0].serviceStartRanges").isEmpty())
                .andReturn();
        evidence("avail-200-no-windows-empty-ranges", result);
    }

    // ------------------------------------------------------------------ 2. 扣除已有预约（本册的核心）

    /**
     * 伪造一条占用 {@code [04:15, 06:00)}（＝起点 05:00 的 45 分钟订单，commute 30＋前缓冲 15）。
     * 它挡掉的起点是开区间 {@code (03:15, 06:45)}，候选 {@code [01:45, 08:15]} 被切成两段：
     * {@code [01:45, 03:15]} 与 {@code [06:45, 08:15]}。另一个技师不受影响。
     */
    @Test
    void activeReservationSplitsCandidateIntoTwoPieces() throws Exception {
        insertReservation("TSTAV-01", tech1,
                DATE + " 04:15:00", DATE + " 06:00:00", DATE + " 06:00:00", DATE + " 05:00:00");

        MvcResult result = mockMvc.perform(get(DATE, "1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andReturn();
        evidence("avail-200-reservation-splits-two-pieces", result);

        // 被占的技师：两段，端点都是闭区间
        assertThat(readRanges(result, 0)).containsExactly(
                new String[]{DATE + "T01:45:00.000Z", DATE + "T03:15:00.000Z"},
                new String[]{DATE + "T06:45:00.000Z", DATE + "T09:00:00.000Z"});
        // 没被占的技师：整段照旧
        assertThat(readRanges(result, 1))
                .containsExactly(new String[]{DATE + "T01:45:00.000Z", DATE + "T09:00:00.000Z"});
    }

    /**
     * <b>端点相接不算冲突</b>：占用 {@code [00:00, 01:45)} 结束在 01:45，起点 02:30
     * （＝01:45 ＋ 通勤 30 ＋ 前缓冲 15）的占用恰好从 01:45 开始——半开区间下两者不重叠，
     * 02:30 必须<b>保留</b>。这条是「开区间减法」的试金石：写成闭区间减法它就变成 02:30 之后的下一段了。
     */
    @Test
    void slotStartingExactlyAtOccupiedEndRemainsAvailable() throws Exception {
        insertReservation("TSTAV-02", tech1,
                DATE + " 00:00:00", DATE + " 01:30:00", DATE + " 01:45:00", DATE + " 01:00:00");

        MvcResult result = mockMvc.perform(get(DATE, "1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andReturn();
        evidence("avail-200-touching-occupancy-keeps-endpoint", result);

        assertThat(readRanges(result, 0))
                .containsExactly(new String[]{DATE + "T02:30:00.000Z", DATE + "T09:00:00.000Z"});
    }

    // ------------------------------------------------------------------ 3. 入参校验

    /** 元素「不存在」与「停用」同样拒绝：对预约意向来说都是目标不可用（本片拍板项，子码 NOT_AVAILABLE）。 */
    @Test
    void unknownOrDisabledServiceItemRejectedAsNotAvailable() throws Exception {
        evidence("avail-400-item-999-not-available", mockMvc.perform(
                        get(DATE, "999", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/serviceItemIds"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NOT_AVAILABLE"))
                .andReturn());

        // BRAKE_PAD 是种子里唯一的停用项——它存在，但停用项不能成为预约意向
        evidence("avail-400-item-5-disabled-not-available", mockMvc.perform(
                        get(DATE, "5", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/serviceItemIds"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NOT_AVAILABLE"))
                .andReturn());
    }

    @Test
    void duplicateServiceItemsRejected() throws Exception {
        evidence("avail-400-duplicate-items", mockMvc.perform(
                        get(DATE, "1,1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/serviceItemIds"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("DUPLICATE"))
                .andReturn());
    }

    @Test
    void serviceItemIdsOverLimitRejected() throws Exception {
        String tooMany = String.join(",", Collections.nCopies(51, "1"));
        evidence("avail-400-items-over-50", mockMvc.perform(
                        get(DATE, tooMany, null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/serviceItemIds"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andReturn());
    }

    @Test
    void missingParamsRejectedAsRequired() throws Exception {
        evidence("avail-400-missing-date", mockMvc.perform(
                        get(null, "1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/date"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                .andReturn());

        evidence("avail-400-missing-serviceItemIds", mockMvc.perform(
                        get(DATE, null, null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/serviceItemIds"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                .andReturn());
    }

    /** 契约样例同款反例：带了时间部分的 date 是格式错误。 */
    @Test
    void malformedDateRejectedAsInvalidFormat() throws Exception {
        evidence("avail-400-date-with-time", mockMvc.perform(
                        get("2026-10-24T09:00:00", "1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/date"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());

        evidence("avail-400-date-not-real", mockMvc.perform(
                        get("2026-02-30", "1", null).with(authentication(jwt(owner1, "ROLE_OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());
    }

    /** 额外 query 参数：与两个列表接口同一口径（D-1），不让 Spring 静默忽略。 */
    @Test
    void extraQueryParamRejectedAsNotAllowed() throws Exception {
        evidence("avail-400-extra-query", mockMvc.perform(get(DATE, "1", null)
                        .with(authentication(jwt(owner1, "ROLE_OWNER")))
                        .param("code", "OIL_FILTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/code"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NOT_ALLOWED"))
                .andReturn());
    }

    // ------------------------------------------------------------------ 4. 角色与幂等

    @Test
    void onlyOwnerCanReadAvailability() throws Exception {
        evidence("avail-403-tech", mockMvc.perform(
                        get(DATE, "1", null).with(authentication(jwt(tech1, "ROLE_TECH"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andReturn());

        evidence("avail-403-admin", mockMvc.perform(
                        get(DATE, "1", null).with(authentication(jwt(admin1, "ROLE_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andReturn());
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        evidence("avail-401-unauthenticated", mockMvc.perform(get(DATE, "1", null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andReturn());
    }

    /** {@code NOT_APPLICABLE_READ}：带键也只是一次普通读取——无重放头、不落幂等记录。 */
    @Test
    void idempotencyKeyOnReadLeavesNoTrace() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(get(DATE, "1", null)
                        .with(authentication(jwt(owner1, "ROLE_OWNER")))
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(header().doesNotExist("Original-Request-Id"))
                .andReturn();
        evidence("avail-200-with-idempotency-key-no-record", result);

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_idempotency WHERE operation = 'getTechnicianAvailability' AND idempotency_key = ?",
                Long.class, key);
        assertThat(rows).as("只读接口不落幂等记录").isZero();
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 造一条<b>最小</b>订单行。只填 NOT NULL 列；两条 CHECK 必须满足：
     * {@code occupied_start < committed_end}、{@code effective_end >= committed_end}。
     *
     * <p>这条数据<b>只存在于测试里</b>（单号前缀 TSTAV-），tearDown 按前缀清掉；
     * 它的形状是「直接指定占用区间」，不走 createOrder——那是 G1-03 的事。
     */
    private void insertReservation(String orderNo, long technicianId, String occupiedStart,
                                   String committedEnd, String effectiveEnd, String serviceStart) {
        jdbcTemplate.update("""
                        INSERT INTO t_service_order (order_no, owner_id, vehicle_id, technician_id, state,
                            service_start, occupied_start, committed_end, effective_end, reservation_active,
                            accept_deadline, booking_items_json, rules_snapshot_json, address_snapshot,
                            mileage_snapshot, model_snapshot, plate_snapshot, created_at, updated_at)
                        VALUES (?, 1, 1, ?, 'RESERVED', ?, ?, ?, ?, 1, '2026-11-01 00:00:00',
                            '[{"service_item_id":1,"quantity":1,"catalog_version":"1"}]',
                            '{"commute_minutes":30,"buffer_before_minutes":15,"buffer_after_minutes":15,
                              "quote_confirm_window_minutes":10,"accept_window_minutes":15}',
                            '测试地址（可用时段测试造数）', 12345, 'COROLLA_2020', '沪A***45',
                            UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                        """,
                orderNo, technicianId, serviceStart, occupiedStart, committedEnd, effectiveEnd);
    }

    private MockHttpServletRequestBuilder get(String date, String serviceItemIds, String technicianId) {
        // 这里必须写全限定调用：类里已有同名三参助手，裸写 get(...) 会递归调到自己（编译都过不了）
        MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/technicians/availability");
        if (date != null) {
            builder = builder.param("date", date);
        }
        if (serviceItemIds != null) {
            builder = builder.param("serviceItemIds", serviceItemIds);
        }
        if (technicianId != null) {
            builder = builder.param("technicianId", technicianId);
        }
        return builder;
    }

    /** 带身份发请求。 {@code get(...)} 的三参版本只管参数，身份在这里叠加。 */
    private JwtAuthenticationToken jwt(long userId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    private long userId(String loginName) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_user WHERE login_name = ?", Long.class, loginName);
        return id == null ? 0 : id;
    }

    private String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 读第 {@code index} 个技师的可选区间，换成字符串对，方便整段比较。 */
    private List<String[]> readRanges(MvcResult result, int index) throws Exception {
        JsonNode ranges = objectMapper.readTree(raw(result))
                .get("data").get("technicians").get(index).get("serviceStartRanges");
        List<String[]> out = new ArrayList<>();
        for (JsonNode range : ranges) {
            out.add(new String[]{range.get("earliest").stringValue(), range.get("latest").stringValue()});
        }
        return out;
    }

    /** 取证行先请求后响应；本接口没有敏感字段，请求参数可以整条打出来。 */
    private void evidence(String what, MvcResult result) throws Exception {
        MockHttpServletRequest req = result.getRequest();
        StringBuilder sb = new StringBuilder();
        sb.append("req=").append(req.getMethod()).append(' ').append(req.getRequestURI());
        if (req.getQueryString() != null) {
            sb.append('?').append(req.getQueryString());
        }
        sb.append(" | resp status=").append(result.getResponse().getStatus());
        sb.append(" headers={");
        List<String> names = new ArrayList<>(result.getResponse().getHeaderNames());
        Collections.sort(names);
        for (String name : names) {
            sb.append(name).append('=').append(result.getResponse().getHeaders(name)).append("; ");
        }
        sb.append("} body=").append(raw(result));
        System.out.println("[G1-02b2] " + what + " = " + ascii(sb.toString()));
    }

    /** 非 ASCII 一律写成 {@code \\uXXXX}：Windows 的 stdout 是 GBK，中文直接进日志会烂。 */
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

