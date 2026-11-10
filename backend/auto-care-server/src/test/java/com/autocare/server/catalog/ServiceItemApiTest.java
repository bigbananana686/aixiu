package com.autocare.server.catalog;

import com.autocare.server.command.CommandResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G1-02b1：{@code GET /api/v1/service-items}（契约 doc10 §4.4），走完整过滤器链，
 * 连<b>真的 MySQL</b>（容器 auto-care-mysql84 @13306），不 H2、不 Mock 库。
 *
 * <p><b>分工如实记录</b>：本片生产代码由助手代写（用户 r1 中改定的协作方式，回报已登记），
 * 断言照契约写、不照实现写——分歧即缺陷。证八组事：
 * <ol>
 *   <li><b>正常</b>：200、恰好 7 字段、serviceItemId 升序、默认分页回显、
 *       与库中 {@code enabled=1} 的行逐条对照（顺序与集合都来自 SQL，不硬编码）；</li>
 *   <li><b>停用项</b>：BRAKE_PAD 不出现、total 只数启用的（同一册用 SQL 对照）；</li>
 *   <li><b>形态</b>：unitPrice 匹配 {@code ^\d+\.\d{2}$} 且是<b>带引号的 JSON 字符串</b>；
 *       serviceItemId / catalogVersion 同为字符串——本片认领的值级规范化对照；</li>
 *   <li><b>分页</b>：越界 400 OUT_OF_RANGE 不截断（page=0、pageSize=101）、
 *       非整数 400 INVALID_FORMAT（page=abc）、边界 page=10000 / pageSize=100 放行；</li>
 *   <li><b>额外 query</b>（D-1 就地裁定）：{@code ?code=}、{@code ?enabled=} → 400 NOT_ALLOWED；</li>
 *   <li><b>角色</b>：owner1 / tech1 / admin1 各 200（三份原始报文），未认证 401；</li>
 *   <li><b>空目录</b>：启用项临时全置 0 → 200 + items=[] + total=0，<b>测试内恢复并核对</b>；</li>
 *   <li><b>幂等</b>：NOT_APPLICABLE_READ——带键无重放头、t_idempotency 无记录。</li>
 * </ol>
 *
 * <p>取证行先记请求再记响应（G1-02a N-04 的口径）；本片无敏感字段，请求整条打出。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceItemApiTest {

    private static final Pattern PRICE_QUOTED = Pattern.compile("\"unitPrice\":\"\\d+\\.\\d{2}\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long owner1;
    private long tech1;
    private long admin1;

    /** 进入每条用例前的 enabled 快照：id → enabled。空目录用例结束后照它核对恢复。 */
    private Map<Long, Integer> enabledSnapshot;

    @BeforeEach
    void setUp() {
        owner1 = userId("owner1");
        tech1 = userId("tech1");
        admin1 = userId("admin1");
        enabledSnapshot = enabledMap();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        // 兜底恢复：任何用例（哪怕断言中途炸了）都不许把种子的 enabled 状态留在库里。
        Map<Long, Integer> now = enabledMap();
        for (Map.Entry<Long, Integer> e : enabledSnapshot.entrySet()) {
            Integer current = now.get(e.getKey());
            if (!e.getValue().equals(current)) {
                jdbcTemplate.update("UPDATE t_service_item SET enabled = ? WHERE id = ?",
                        e.getValue(), e.getKey());
                System.out.println("[G1-02b1] restored enabled of item " + e.getKey()
                        + " from " + current + " to " + e.getValue());
            }
        }
    }

    // ================================================== 1. 正常 + 停用项 + 形态

    /**
     * 正常路径一册全打：200；items 与库中 {@code enabled=1} 的行<b>逐条同序同集</b>
     * （期望值全部来自 SQL，不硬编码种子内容——种子改了用例不许跟着撒谎）；
     * BRAKE_PAD（停用）零出现；每项恰好 7 个字段；金额与 ID/版本都是带引号的字符串。
     */
    @Test
    void listReturnsEnabledItemsInAscendingOrderWithDefaultPaging() throws Exception {
        MvcResult result = mockMvc.perform(getFor(owner1, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andReturn();
        evidence("list-200-default-paging", result);

        List<String> expectedCodes = jdbcTemplate.queryForList(
                "SELECT code FROM t_service_item WHERE enabled = 1 ORDER BY id", String.class);
        assertThat(expectedCodes).as("seed must contain enabled rows for this test to mean anything")
                .isNotEmpty();

        JsonNode items = node(result, "data", "items");
        List<Long> ids = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(Long.parseLong(item.get("serviceItemId").stringValue()));
            codes.add(item.get("code").stringValue());
        }
        assertThat(codes).as("items must equal the enabled rows, in id order").isEqualTo(expectedCodes);
        assertThat(ids).as("serviceItemId must be ascending").isSorted();
        assertThat(readLong(result, "data", "total"))
                .as("total counts only enabled rows")
                .isEqualTo(enabledCount());

        // 停用项绝不能出现：items 里没有，total 不含它（上面那条 total 对照已含），正文里零命中
        assertThat(raw(result)).as("BRAKE_PAD is disabled and must never leak").doesNotContain("BRAKE_PAD");

        // 恰好 7 个字段：enabled/createdAt/updatedAt 是 ADMIN 投影（G2-01a），这里多一个都算缺陷
        for (JsonNode item : items) {
            // fieldNames() 在 Jackson 3 里没了；转 Map 取 keySet 是版本无关的等价写法
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = objectMapper.convertValue(item, Map.class);
            assertThat(fields.keySet()).containsExactlyInAnyOrder(
                    "serviceItemId", "code", "name", "durationMinutes",
                    "unitPrice", "currency", "catalogVersion");
        }

        // 形态（值级规范化）：金额固定两位、ID/版本是字符串——在<b>原始文本</b>上验带引号，
        // 解析后的类型断言会被 Jackson 的宽松转换骗过去（G1-02a N-01 的教训反过来用）。
        String body = raw(result);
        assertThat(PRICE_QUOTED.matcher(body).results().count())
                .as("every unitPrice must be a quoted two-decimal string")
                .isEqualTo(items.size());
        assertThat(body).containsPattern("\"serviceItemId\":\"\\d+\"");
        assertThat(body).containsPattern("\"catalogVersion\":\"\\d+\"");
    }

    /** 三角色都可读（§4.4）：owner1 / tech1 / admin1 各一次 200，三份原始报文分别落证。 */
    @Test
    void allThreeRolesCanRead() throws Exception {
        evidence("role-200-owner1", mockMvc.perform(getFor(owner1, null, null))
                .andExpect(status().isOk()).andReturn());
        evidence("role-200-tech1", mockMvc.perform(getFor(tech1, null, null))
                .andExpect(status().isOk()).andReturn());
        evidence("role-200-admin1", mockMvc.perform(getFor(admin1, null, null))
                .andExpect(status().isOk()).andReturn());
    }

    /** 未认证 → 401 UNAUTHENTICATED（不带 authentication post-processor，走真实安全链）。 */
    @Test
    void unauthenticatedIs401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/service-items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andReturn();
        evidence("unauth-401", result);
    }

    // ================================================== 2. 分页

    /** 越界 400 OUT_OF_RANGE，<b>不自动截断</b>：page=0、pageSize=101 都必须被拒。 */
    @Test
    void pagingOutOfRangeIsRejectedNotTruncated() throws Exception {
        evidence("paging-400-page-0", mockMvc.perform(getFor(owner1, "0", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/page"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andReturn());

        evidence("paging-400-pageSize-101", mockMvc.perform(getFor(owner1, "1", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/pageSize"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andReturn());
    }

    /** 非整数 → 400 INVALID_FORMAT（形式不合法，不是越界、更不是 500）。 */
    @Test
    void nonNumericPageIs400InvalidFormat() throws Exception {
        evidence("paging-400-page-abc", mockMvc.perform(getFor(owner1, "abc", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/page"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());
    }

    /** 边界放行：page=10000 / pageSize=100 → 200；翻到空页是空数组，total 不受翻页影响。 */
    @Test
    void pagingBoundariesAccepted() throws Exception {
        MvcResult result = mockMvc.perform(getFor(owner1, "10000", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(10000))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andReturn();
        evidence("paging-200-boundaries-empty-page", result);

        assertThat(readLong(result, "data", "total"))
                .as("an empty page is not an empty catalog").isEqualTo(enabledCount());
    }

    // ================================================== 3. 额外 query 参数（D-1）

    /** 只放行 page/pageSize；其余 → 400 /参数名 NOT_ALLOWED（D-1 就地裁定，N-06 同款）。 */
    @Test
    void extraQueryParamsAreRejected() throws Exception {
        evidence("extra-query-400-code", mockMvc.perform(getFor(owner1, null, null).param("code", "OIL_FILTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/code"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NOT_ALLOWED"))
                .andReturn());

        evidence("extra-query-400-enabled", mockMvc.perform(getFor(owner1, null, null).param("enabled", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/enabled"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NOT_ALLOWED"))
                .andReturn());
    }

    // ================================================== 4. 空目录（含恢复与核对）

    /**
     * 启用项临时全置 0 → 200 + items=[] + total=0；<b>finally 里按快照恢复</b>，
     * 恢复后逐行核对与进入前一致（@AfterEach 再兜一次底）。
     */
    @Test
    void emptyCatalogWhenAllDisabledRestoresAfterwards() throws Exception {
        try {
            jdbcTemplate.update("UPDATE t_service_item SET enabled = 0");

            MvcResult result = mockMvc.perform(getFor(owner1, null, null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andReturn();
            evidence("empty-catalog-200-empty-array-total-0", result);
        } finally {
            for (Map.Entry<Long, Integer> e : enabledSnapshot.entrySet()) {
                jdbcTemplate.update("UPDATE t_service_item SET enabled = ? WHERE id = ?",
                        e.getValue(), e.getKey());
            }
        }

        assertThat(enabledMap()).as("seed enabled state must be restored row by row")
                .isEqualTo(enabledSnapshot);
    }

    // ================================================== 5. NOT_APPLICABLE_READ

    /** 带幂等键也不产生重放头、不落幂等记录——只读接口的幂等类别（§5.1）。 */
    @Test
    void idempotencyKeyOnReadIsIgnored() throws Exception {
        String key = UUID.randomUUID().toString();
        evidence("read-with-idempotency-key-no-record", mockMvc.perform(getFor(owner1, null, null)
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(CommandResponses.HEADER_REPLAYED))
                .andExpect(header().doesNotExist(CommandResponses.HEADER_ORIGINAL_REQUEST_ID))
                .andReturn());

        Long records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_idempotency WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
                Long.class, owner1, "listServiceItems", key);
        assertThat(records == null ? 0 : records)
                .as("NOT_APPLICABLE_READ: no idempotency record may be written").isZero();
    }

    // ================================================== 小工具

    private MockHttpServletRequestBuilder getFor(long actorId, String page, String pageSize) {
        MockHttpServletRequestBuilder builder = get("/api/v1/service-items")
                .with(authentication(jwt(actorId,
                        actorId == tech1 ? "ROLE_TECH" : actorId == admin1 ? "ROLE_ADMIN" : "ROLE_OWNER")));
        if (page != null) {
            builder = builder.param("page", page);
        }
        if (pageSize != null) {
            builder = builder.param("pageSize", pageSize);
        }
        return builder;
    }

    private long enabledCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_service_item WHERE enabled = 1", Long.class);
        return n == null ? 0 : n;
    }

    private Map<Long, Integer> enabledMap() {
        Map<Long, Integer> map = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, enabled FROM t_service_item ORDER BY id", rs -> {
            map.put(rs.getLong("id"), rs.getInt("enabled"));
        });
        return map;
    }

    private long userId(String loginName) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_user WHERE login_name = ?", Long.class, loginName);
        return id == null ? 0 : id;
    }

    /** 造一个真的 Jwt 装进安全上下文（controller 只读 sub；验签是安全链的活，MockMvc 不重签）。 */
    private JwtAuthenticationToken jwt(long userId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    private String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 按路径逐层取节点（工程自己的 ObjectMapper，Jackson 3）。 */
    private JsonNode node(MvcResult result, String... path) throws Exception {
        JsonNode current = objectMapper.readTree(raw(result));
        for (String step : path) {
            current = current.get(step);
        }
        return current;
    }

    private long readLong(MvcResult result, String... path) throws Exception {
        return node(result, path).asLong();
    }

    /** 取证：先记请求（方法 + URI + query），再记状态行 + 全部响应头 + 正文；非 ASCII 转义。 */
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
        System.out.println("[G1-02b1] " + what + " = " + ascii(sb.toString()));
    }

    /** 非 ASCII 写成 {@code \\uXXXX}（Windows stdout 是 GBK，中文直出会乱码，证据要能被比对原文）。 */
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
