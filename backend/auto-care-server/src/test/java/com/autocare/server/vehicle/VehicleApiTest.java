package com.autocare.server.vehicle;

import com.autocare.server.command.CommandResponses;
import com.autocare.server.command.IdempotencyKey;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G1-02a：车辆三个接口，走<b>完整过滤器链</b>（编号 → 大小 → 键闸门 → 安全链 → 路由 → service → 执行器
 * → 全局异常处理器），连的是<b>真的 MySQL</b>（容器 auto-care-mysql84 @13306），不用 H2、不 Mock 库。
 *
 * <p><b>生产代码是用户本人写的，这一册是助手补的取证。</b>所以断言一律照
 * {@code 10-G0-G1详细契约.md §4.1—§4.3} 与 {@code 01-公共接口契约.md §5} 写，不照实现写——
 * 出现分歧时以契约文字为准，测出来的偏差就是缺陷。
 *
 * <p>这一册证五组事：
 * <ol>
 *   <li><b>正常路径</b>：list 的分页与升序、create 的 201 + 相对 Location + rowVersion="1"、
 *       update 的 rowVersion 递增；</li>
 *   <li><b>失败路径</b>：缺键 / 非法键 / body 夹带 ownerId / 里程越界 / 分页越界 / TECH 403 /
 *       改别人的车 404 同形 / 版本不符 409；</li>
 *   <li><b>幂等</b>：同键同内容重放（业务只落一行）、同键异内容 409；</li>
 *   <li><b>必验②</b>：归属检查<b>先于</b>重放——车改走之后同键重放必须 404，不能把旧 data 递回去；</li>
 *   <li><b>必验①（按 ACCEPT-014 更正后的口径）</b>：ID／版本类字段必须是<b>字符串</b>形态的十进制整数——
 *       JSON 数值 400 {@code INVALID_TYPE}（契约 §2.1 反例栏「0、数字3、"03" 拒绝」＋§1.5），
 *       {@code "0"} 与 {@code "01"} 400 {@code INVALID_FORMAT}。</li>
 * </ol>
 *
 * <p><b>每个用例都往 stdout 打一行原始报文</b>（状态行 + 全部响应头 + 正文，非 ASCII 按
 * {@code \\uXXXX} 转义），因为验收要看的是原始报文——「404 的响应体里到底有没有旧 data 的字段」
 * 这种事，光看一句"断言通过"等于采信自述。
 *
 * <p><b>取证标签一律写 ASCII。</b>Windows 下 Java 的 stdout 按 GBK 出字节，标签里的中文
 * 会变成乱码——证据是要被人拿去读的。中文只出现在被抓的正文里，正文由 {@link #ascii} 转义。
 *
 * <p><b>请求体一律按 UTF-8 出字节</b>，不走 MockMvc 的字符串编码（默认 ISO-8859-1，中文会烂）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VehicleApiTest {

    /** 本册写进库里的车，靠这个 model_code 认领与清理——种子里没有这个值。 */
    private static final String TEST_MODEL = "G1_02A_TEST";
    /** 改车型那条用例会把车改名成这个，收尾时也要认。 */
    private static final String TEST_MODEL_CHANGED = "G1_02A_CHANGED";
    /** 车牌原文：只许出现在请求里，响应、日志、证据文件都不许有它。 */
    private static final String PLATE_RAW = "A1234567";
    /** 按 §4.2 的规则，A1234567 该脱敏成 A1***67。 */
    private static final String PLATE_MASKED = "A1***67";

    private static final String FIRST_REQUEST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String SECOND_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private static final String BODY_CREATE =
            "{\"modelCode\":\"" + TEST_MODEL + "\",\"plateNumber\":\"" + PLATE_RAW + "\",\"mileage\":1234}";
    private static final String BODY_CREATE_NO_PLATE =
            "{\"modelCode\":\"" + TEST_MODEL + "\",\"mileage\":0}";
    private static final String BODY_CREATE_WITH_OWNER =
            "{\"modelCode\":\"" + TEST_MODEL + "\",\"mileage\":1,\"ownerId\":\"1\"}";
    private static final String BODY_CREATE_BAD_MILEAGE =
            "{\"modelCode\":\"" + TEST_MODEL + "\",\"mileage\":-1}";
    private static final String BODY_CREATE_OTHER_CONTENT =
            "{\"modelCode\":\"" + TEST_MODEL + "\",\"plateNumber\":\"" + PLATE_RAW + "\",\"mileage\":999}";

    private static final String BODY_UPDATE = "{\"expectedRowVersion\":\"1\",\"mileage\":2000}";
    private static final String BODY_UPDATE_VERSION_AS_NUMBER = "{\"expectedRowVersion\":1,\"mileage\":2000}";
    private static final String BODY_UPDATE_LEADING_ZERO = "{\"expectedRowVersion\":\"01\",\"mileage\":2000}";
    private static final String BODY_UPDATE_STALE_VERSION = "{\"expectedRowVersion\":\"9\",\"mileage\":2000}";
    private static final String BODY_UPDATE_NO_VERSION = "{\"mileage\":2000}";
    private static final String BODY_UPDATE_NO_MUTABLE_FIELD = "{\"expectedRowVersion\":\"1\"}";
    private static final String BODY_UPDATE_EXPLICIT_NULL = "{\"expectedRowVersion\":\"1\",\"modelCode\":null}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long owner1;
    private long owner2;
    private long tech1;

    /** 本用例用过的幂等键，收尾时按这些键清干净，不让记录留在库里干扰别的册子。 */
    private final List<String> usedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        owner1 = userId("owner1");
        owner2 = userId("owner2");
        tech1 = userId("tech1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        // 按**两个** model_code 删：updateCanChangeModelCodeAndRewrapPlate 会把测试车改名，
        // 只按 TEST_MODEL 删会漏掉它——r2 收尾时就漏过一行，库里的种子车数从 2 变成 4，别再犯。
        jdbcTemplate.update("DELETE FROM t_vehicle WHERE model_code IN (?, ?)",
                TEST_MODEL, TEST_MODEL_CHANGED);
        if (!usedKeys.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(usedKeys.size(), "?"));
            jdbcTemplate.update(
                    "DELETE FROM t_idempotency WHERE idempotency_key IN (" + placeholders + ")",
                    usedKeys.toArray());
        }
        usedKeys.clear();
    }

    // ================================================================== 1. 正常路径

    /**
     * 列表：200，items 按 vehicleId 升序，total 是<b>当前角色过滤后</b>的数量，
     * 分页参数省略时取默认（page=1、pageSize=20）。
     */
    @Test
    void listReturnsOwnerScopedItemsInAscendingIdOrder() throws Exception {
        createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);
        createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE_NO_PLATE);

        MvcResult result = mockMvc.perform(list(owner1, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andReturn();
        evidence("list-200-default-paging", result);

        List<Long> ids = readIds(result);
        assertThat(ids).as("at least two test vehicles plus the seeded one").hasSizeGreaterThanOrEqualTo(3);
        assertThat(ids).as("items must be ascending by vehicleId").isSorted();

        long total = readLong(result, "data", "total");
        assertThat(total).as("total is this owner's count").isEqualTo(ids.size());
        assertThat(total).as("total must not be the whole-table count (owner2 has one too)")
                .isLessThan(totalInDb());
    }

    /** 新建：201 + 相对 Location + rowVersion 初始 "1" + 车牌已脱敏。 */
    @Test
    void createReturns201WithLocationVersionOneAndMaskedPlate() throws Exception {
        MvcResult result = mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern("/api/v1/vehicles/\\d+")))
                .andExpect(jsonPath("$.data.modelCode").value(TEST_MODEL))
                .andExpect(jsonPath("$.data.rowVersion").value("1"))
                .andExpect(jsonPath("$.data.mileage").value(1234))
                .andExpect(jsonPath("$.data.plateMasked").value(PLATE_MASKED))
                .andExpect(jsonPath("$.data.vehicleId").isNotEmpty())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        evidence("create-201", result);

        String body = raw(result);
        assertThat(body).as("the raw plate must not be echoed back").doesNotContain(PLATE_RAW);
        assertThat(body).as("plate_mask_source never appears in a response").doesNotContain("plateMaskSource");
    }

    /** 没提供车牌：占位规则由本片定并留证——写常量「未提供」。 */
    @Test
    void createWithoutPlateUsesDocumentedPlaceholder() throws Exception {
        MvcResult result = mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE_NO_PLATE))
                .andExpect(status().isCreated())
                .andReturn();
        evidence("create-201-no-plate", result);

        assertThat(raw(result))
                .as("placeholder when plate was not provided: U+672A U+63D0 U+4F9B")
                .contains("未提供");
    }

    /** 修改：200，rowVersion 递增，库里跟着变。 */
    @Test
    void updateIncrementsRowVersion() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        MvcResult result = mockMvc.perform(update(owner1, vehicleId, newKey(), FIRST_REQUEST_ID, BODY_UPDATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").value(String.valueOf(vehicleId)))
                .andExpect(jsonPath("$.data.rowVersion").value("2"))
                .andExpect(jsonPath("$.data.mileage").value(2000))
                .andReturn();
        evidence("update-200", result);

        assertThat(currentVersion(vehicleId)).as("db row_version follows").isEqualTo(2L);
    }

    // ================================================================== 2. 失败路径

    @Test
    void createWithoutKeyIs400Required() throws Exception {
        MvcResult result = mockMvc.perform(create(owner1, null, FIRST_REQUEST_ID, BODY_CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/headers/Idempotency-Key"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                .andReturn();
        evidence("create-400-missing-key", result);

        assertThat(testVehicleCount()).as("a rejected request leaves no business row").isZero();
    }

    @Test
    void createWithMalformedKeyIs400InvalidFormat() throws Exception {
        MvcResult result = mockMvc.perform(create(owner1, "short", FIRST_REQUEST_ID, BODY_CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/headers/Idempotency-Key"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn();
        evidence("create-400-malformed-key", result);

        assertThat(testVehicleCount()).isZero();
    }

    /** body 夹带 ownerId：必须 400 UNKNOWN_FIELD，绝不能静默忽略、更不能被当成归属来源。 */
    @Test
    void createWithOwnerIdInBodyIs400UnknownField() throws Exception {
        MvcResult result = mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE_WITH_OWNER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/ownerId"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("UNKNOWN_FIELD"))
                .andReturn();
        evidence("create-400-ownerId-in-body", result);

        assertThat(testVehicleCount()).isZero();
    }

    @Test
    void createWithMileageOutOfRangeIs400() throws Exception {
        MvcResult result = mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE_BAD_MILEAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/mileage"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andReturn();
        evidence("create-400-mileage-out-of-range", result);
    }

    @Test
    void listWithPagingOutOfRangeIs400AndNotTruncated() throws Exception {
        evidence("list-400-page-0", mockMvc.perform(list(owner1, "0", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/page"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andReturn());

        evidence("list-400-pageSize-101", mockMvc.perform(list(owner1, "1", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/pageSize"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andReturn());
    }

    /** TECH 调三个接口里的 list → 403，且响应里不带任何资源细节。 */
    @Test
    void techCannotListVehicles() throws Exception {
        MvcResult result = mockMvc.perform(list(tech1, null, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andReturn();
        evidence("list-403-tech", result);

        String body = raw(result);
        assertThat(body).as("403 must not leak resource details")
                .doesNotContain("items").doesNotContain("vehicleId");
    }

    /**
     * 改别人的车 与 改不存在的车：<b>统一 404、同形</b>。
     *
     * <p>「同形」是重点：两种情况下的错误码与字段集合必须完全一致，否则从响应差异就能反推出
     * 「这台车存在但不属于你」——等于泄露了别人的资源是否存在。断言方式是把两段正文去掉
     * requestId（每次都不同，不参与比较）后逐字比对。
     */
    @Test
    void updateOthersVehicleIs404SameShapeAsMissing() throws Exception {
        long mine = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        MvcResult notMine = mockMvc.perform(update(owner2, mine, newKey(), FIRST_REQUEST_ID, BODY_UPDATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andReturn();
        evidence("update-404-someone-elses-vehicle", notMine);

        MvcResult missing = mockMvc.perform(update(owner1, 999_999_999L, newKey(), FIRST_REQUEST_ID, BODY_UPDATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andReturn();
        evidence("update-404-nonexistent-vehicle", missing);

        assertThat(stripRequestId(notMine)).as("both 404s must have the same shape")
                .isEqualTo(stripRequestId(missing));
        assertThat(raw(notMine)).doesNotContain("vehicleId").doesNotContain("plateMasked");
    }

    @Test
    void updateWithStaleVersionIs409VersionMismatch() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        MvcResult result = mockMvc.perform(
                        update(owner1, vehicleId, newKey(), FIRST_REQUEST_ID, BODY_UPDATE_STALE_VERSION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/expectedRowVersion"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("VERSION_MISMATCH"))
                .andReturn();
        evidence("update-409-version-mismatch", result);

        assertThat(currentVersion(vehicleId)).as("a failed update does not bump the version").isEqualTo(1L);
    }

    @Test
    void updateWithoutExpectedRowVersionIs400Required() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        evidence("update-400-missing-expectedRowVersion",
                mockMvc.perform(update(owner1, vehicleId, newKey(), FIRST_REQUEST_ID, BODY_UPDATE_NO_VERSION))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/expectedRowVersion"))
                        .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                        .andReturn());
    }

    @Test
    void updateWithoutAnyMutableFieldIs400() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        evidence("update-400-no-mutable-field",
                mockMvc.perform(update(owner1, vehicleId, newKey(), FIRST_REQUEST_ID, BODY_UPDATE_NO_MUTABLE_FIELD))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                        .andReturn());
    }

    @Test
    void updateWithExplicitNullIs400NullNotAllowed() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        evidence("update-400-explicit-null",
                mockMvc.perform(update(owner1, vehicleId, newKey(), FIRST_REQUEST_ID, BODY_UPDATE_EXPLICIT_NULL))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/modelCode"))
                        .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NULL_NOT_ALLOWED"))
                        .andReturn());
    }

    // ================================================================== 3. 幂等

    /** 同键同内容重放：原 201 + 原 Location + 原 data ＋ 两个重放头，业务只落一行。 */
    @Test
    void createReplayReturnsOriginal201WithReplayHeadersAndSingleRow() throws Exception {
        String key = newKey();

        MvcResult first = mockMvc.perform(create(owner1, key, FIRST_REQUEST_ID, BODY_CREATE))
                .andExpect(status().isCreated())
                .andReturn();
        evidence("create-201-first-run", first);
        String firstLocation = first.getResponse().getHeader(HttpHeaders.LOCATION);

        MvcResult replay = mockMvc.perform(create(owner1, key, SECOND_REQUEST_ID, BODY_CREATE))
                .andExpect(status().isCreated())
                .andExpect(header().string(CommandResponses.HEADER_REPLAYED, "true"))
                .andExpect(header().string(CommandResponses.HEADER_ORIGINAL_REQUEST_ID, FIRST_REQUEST_ID))
                .andExpect(header().string(HttpHeaders.LOCATION, firstLocation))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, SECOND_REQUEST_ID))
                .andExpect(jsonPath("$.requestId").value(SECOND_REQUEST_ID))
                .andReturn();
        evidence("create-201-replay-same-key-same-content", replay);

        assertThat(testVehicleCount()).as("replay must not create a second business row").isEqualTo(1);
    }

    /** 同键异内容：409 IDEMPOTENCY_KEY_REUSED，字段级子码 CONFLICT。 */
    @Test
    void sameKeyDifferentContentIs409IdempotencyKeyReused() throws Exception {
        String key = newKey();

        createOk(owner1, key, FIRST_REQUEST_ID, BODY_CREATE);

        MvcResult result = mockMvc.perform(create(owner1, key, SECOND_REQUEST_ID, BODY_CREATE_OTHER_CONTENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("CONFLICT"))
                .andReturn();
        evidence("create-409-same-key-different-content", result);

        assertThat(testVehicleCount()).as("a conflict creates no second business row").isEqualTo(1);
    }

    // ================================================================== 4. 必验② 归属先于重放

    /**
     * <b>必验②</b>：车被改走到别人名下之后，用<b>同一个键</b>重放 → <b>404</b>，不是把旧 data 递回去。
     *
     * <p>这条能钉死两件事：service 里那句"这台车还归不归他"的检查确实排在 {@code execute}
     * <b>之前</b>（否则重放会直接命中记录、跳过检查），以及机制里的可见性复核被真的接上了
     * （否则旧 data 会照原样还回来）。
     */
    @Test
    void replayIs404WhenVehicleWasMovedAwayFromOwner() throws Exception {
        String key = newKey();
        createOk(owner1, key, FIRST_REQUEST_ID, BODY_CREATE);

        long vehicleId = onlyTestVehicleId();
        jdbcTemplate.update("UPDATE t_vehicle SET owner_id = ? WHERE id = ?", owner2, vehicleId);

        MvcResult result = mockMvc.perform(create(owner1, key, SECOND_REQUEST_ID, BODY_CREATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(header().doesNotExist(CommandResponses.HEADER_REPLAYED))
                .andExpect(header().doesNotExist(CommandResponses.HEADER_ORIGINAL_REQUEST_ID))
                .andReturn();
        evidence("MUST-VERIFY-2-replay-after-ownership-moved-404", result);

        String body = raw(result);
        assertThat(body).as("hidden uniformly: no old data fields may appear")
                .doesNotContain("\"data\"")
                .doesNotContain("vehicleId")
                .doesNotContain("plateMasked")
                .doesNotContain("rowVersion");

        assertThat(testVehicleCount()).as("the row is still there, only its owner changed").isEqualTo(1);
    }

    // ================================================================== 5. 必验① 值级规范化

    /**
     * <b>必验①（按 ACCEPT-014 更正后的口径）</b>：ID／版本类字段必须是<b>字符串</b>形态的十进制整数。
     *
     * <p>契约 §2.1 第 2 行反例栏明文「0、数字3、"03" 拒绝」，§1.5 的 {@code INVALID_TYPE} 定义就是
     * 「ID/版本写成数值」。要字符串是为了避开 JS Number 的精度上限（§2.1 正例专门给了
     * {@code "9007199254740993"}）。
     *
     * <p><b>这条 r1 测反了</b>：原用例断言「数字 {@code 1} 与字符串 {@code "1"} 算同一个值」，
     * 把 Jackson 的强制转换固化成了「正确」。ACCEPT-014 裁定以契约为准——数值形态必须 400。
     *
     * <p>摘要一侧的口径不变：进摘要的是<b>已校验</b>的十进制字符串（{@code RequestDigest} 只做结构
     * 规范化，值级规范化归字段校验）。数值形态在校验那一步就被拒，根本到不了摘要。
     */
    @Test
    void mustVerify1VersionMustBeCanonicalDecimalString() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);
        String key = newKey();

        MvcResult first = mockMvc.perform(update(owner1, vehicleId, key, FIRST_REQUEST_ID, BODY_UPDATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").value("2"))
                .andReturn();
        evidence("MUST-VERIFY-1-version-as-string-ok", first);

        // 同一个键再来一次：库里版本已经是 2，请求里还写着 "1"。它必须重放成功——因为动态的
        // 版本比对在重放路径上要被跳过（契约 §5.3）；要是没跳过，这里会变成 409。
        MvcResult replay = mockMvc.perform(update(owner1, vehicleId, key, SECOND_REQUEST_ID, BODY_UPDATE))
                .andExpect(status().isOk())
                .andExpect(header().string(CommandResponses.HEADER_REPLAYED, "true"))
                .andExpect(jsonPath("$.data.rowVersion").value("2"))
                .andReturn();
        evidence("MUST-VERIFY-1-update-replay-skips-version-check", replay);

        MvcResult asNumber = mockMvc.perform(
                        update(owner1, vehicleId, newKey(), FIRST_REQUEST_ID, BODY_UPDATE_VERSION_AS_NUMBER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/expectedRowVersion"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_TYPE"))
                .andReturn();
        evidence("MUST-VERIFY-1-version-as-number-400-INVALID_TYPE", asNumber);

        evidence("MUST-VERIFY-1-version-zero-400", mockMvc.perform(update(owner1, vehicleId, newKey(),
                        FIRST_REQUEST_ID, "{\"expectedRowVersion\":\"0\",\"mileage\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/expectedRowVersion"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());

        evidence("MUST-VERIFY-1-version-leading-zero-400", mockMvc.perform(update(owner1, vehicleId, newKey(),
                        FIRST_REQUEST_ID, BODY_UPDATE_LEADING_ZERO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/expectedRowVersion"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());

        assertThat(currentVersion(vehicleId)).as("后面三次都被拒，版本停在 2").isEqualTo(2L);
    }

    // ================================================================== 6. 契约口径补测（ACCEPT-014 裁定后）

    /**
     * 列表不接受被禁止的筛选字段：{@code ?ownerId=} 必须 400，不能静默忽略。
     *
     * <p>契约 §4.1：「请求不接受 {@code ownerId} 等字段」，错误表列「400（…或<b>被禁止的筛选字段</b>）」，
     * §1.5 的子码 {@code NOT_ALLOWED} 写的就是「该筛选/字段在此场景被禁止（用他人身份做筛选）」。
     * Spring 对 handler 没声明的 query 参数是静默忽略的——所以这条不主动查就永远发不现。
     */
    @Test
    void listRejectsForbiddenQueryParams() throws Exception {
        MvcResult ownerId = mockMvc.perform(listWithExtra(owner1, "ownerId", String.valueOf(owner2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/ownerId"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NOT_ALLOWED"))
                .andReturn();
        evidence("list-400-forbidden-query-ownerId", ownerId);

        evidence("list-400-forbidden-query-unknown", mockMvc.perform(listWithExtra(owner1, "sort", "mileage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/sort"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NOT_ALLOWED"))
                .andReturn());
    }

    /** 分页参数非整数 → 400 {@code INVALID_FORMAT}（口径 D-3：形式不合法，不是越界、更不是 500）。 */
    @Test
    void listWithNonNumericPagingIs400InvalidFormat() throws Exception {
        evidence("list-400-page-abc", mockMvc.perform(list(owner1, "abc", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/page"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"))
                .andReturn());
    }

    /**
     * 分页的<b>接受边界</b>（page=10000、pageSize=100）必须放行，且翻到没数据的那一页时是
     * 200 ＋ <b>空数组</b>而不是 null（契约 §2.2：空集合用 {@code []}）。
     *
     * <p>只测越界会漏掉「边界值被误判成越界」这一类；顺带用第 10000 页天然构造出空集路径，
     * 不用为了测空数组去造一个没有车的账号。
     */
    @Test
    void listAcceptsPagingBoundariesAndReturnsEmptyArray() throws Exception {
        MvcResult result = mockMvc.perform(list(owner1, "10000", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(10000))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andReturn();
        evidence("list-200-page-10000-pageSize-100-empty-array", result);

        assertThat(readLong(result, "data", "total")).as("空页不等于 total=0").isGreaterThan(0L);
    }

    /** 新建时必填字段缺失或显式 null：缺失 400 {@code REQUIRED}，显式 null 400 {@code NULL_NOT_ALLOWED}。 */
    @Test
    void createFieldNullAndMissingRulesHold() throws Exception {
        evidence("create-400-missing-mileage", mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID,
                        "{\"modelCode\":\"" + TEST_MODEL + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/mileage"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"))
                .andReturn());

        evidence("create-400-null-modelCode", mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID,
                        "{\"modelCode\":null,\"mileage\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/modelCode"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NULL_NOT_ALLOWED"))
                .andReturn());

        evidence("create-400-null-mileage", mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID,
                        "{\"modelCode\":\"" + TEST_MODEL + "\",\"mileage\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/mileage"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NULL_NOT_ALLOWED"))
                .andReturn());

        // 车牌可选，但「不可 null（省略即不提供）」——显式 null 要和"没给"分开（§4.2 请求表）
        evidence("create-400-null-plateNumber", mockMvc.perform(create(owner1, newKey(), FIRST_REQUEST_ID,
                        "{\"modelCode\":\"" + TEST_MODEL + "\",\"plateNumber\":null,\"mileage\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/plateNumber"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NULL_NOT_ALLOWED"))
                .andReturn());

        assertThat(testVehicleCount()).as("四个被拒的请求都不留业务行").isZero();
    }

    /** TECH 调三个接口都该 403（完成条件只点名 list，契约 §4.2／§4.3 三个都要）。 */
    @Test
    void techCannotCreateOrUpdateVehicle() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        evidence("create-403-tech", mockMvc.perform(post("/api/v1/vehicles")
                        .with(authentication(jwt(tech1, "ROLE_TECH")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyKey.HEADER_NAME, newKey())
                        .content(BODY_CREATE.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andReturn());

        evidence("update-403-tech", mockMvc.perform(patch("/api/v1/vehicles/" + vehicleId)
                        .with(authentication(jwt(tech1, "ROLE_TECH")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyKey.HEADER_NAME, newKey())
                        .content(BODY_UPDATE.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andReturn());

        assertThat(currentVersion(vehicleId)).isEqualTo(1L);
    }

    /** update 不能 PATCH 受保护字段（§4.3：{@code vehicleId}／{@code ownerId}／{@code rowVersion} 传了即未知字段）。 */
    @Test
    void updateWithProtectedFieldsIs400UnknownField() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        evidence("update-400-body-vehicleId", mockMvc.perform(update(owner1, vehicleId, newKey(),
                        FIRST_REQUEST_ID, "{\"expectedRowVersion\":\"1\",\"vehicleId\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/vehicleId"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("UNKNOWN_FIELD"))
                .andReturn());

        evidence("update-400-body-ownerId", mockMvc.perform(update(owner1, vehicleId, newKey(),
                        FIRST_REQUEST_ID, "{\"expectedRowVersion\":\"1\",\"ownerId\":\"2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/ownerId"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("UNKNOWN_FIELD"))
                .andReturn());

        evidence("update-400-body-rowVersion", mockMvc.perform(update(owner1, vehicleId, newKey(),
                        FIRST_REQUEST_ID, "{\"expectedRowVersion\":\"1\",\"rowVersion\":\"2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/rowVersion"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("UNKNOWN_FIELD"))
                .andReturn());
    }

    /** 改车型与车牌：车牌要按新值重新脱敏，且原文不回显（复用 create 那条脱敏规则）。 */
    @Test
    void updateCanChangeModelCodeAndRewrapPlate() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);

        MvcResult result = mockMvc.perform(update(owner1, vehicleId, newKey(), FIRST_REQUEST_ID,
                        "{\"expectedRowVersion\":\"1\",\"modelCode\":\"" + TEST_MODEL_CHANGED
                                + "\",\"plateNumber\":\"B7654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelCode").value(TEST_MODEL_CHANGED))
                .andExpect(jsonPath("$.data.plateMasked").value("B7***21"))
                .andExpect(jsonPath("$.data.rowVersion").value("2"))
                .andReturn();
        evidence("update-200-modelCode-and-plate-re-masked", result);

        assertThat(raw(result)).as("新车牌原文也不许回显").doesNotContain("B7654321");
    }

    /** 列表是 {@code NOT_APPLICABLE_READ}：带幂等键既不落记录、也不重放，就是一次普通读取。 */
    @Test
    void listWithIdempotencyKeyIsAPlainRead() throws Exception {
        String key = newKey();
        MvcResult result = mockMvc.perform(list(owner1, null, null).header(IdempotencyKey.HEADER_NAME, key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(CommandResponses.HEADER_REPLAYED))
                .andExpect(header().doesNotExist(CommandResponses.HEADER_ORIGINAL_REQUEST_ID))
                .andReturn();
        evidence("list-200-with-idempotency-key-no-record", result);

        assertThat(idempotencyCount(owner1, "listVehicles", key))
                .as("NOT_APPLICABLE_READ：不读也不写幂等记录").isZero();
    }

    /**
     * <b>必验② 的 updateVehicle 路径</b>：车改走之后，用 {@code updateVehicle} 的同一个键重放 → 404。
     *
     * <p>与 create 那条不同，这条证的是 service 在 {@code execute} <b>之前</b>那句归属检查
     * （{@code VehicleService.update} 里 {@code findOwned} → 404 排在 {@code execute} 前面），
     * 而不只是机制里的可见性复核——两条路都会 404，但只有这条会先被业务侧拦下。
     */
    @Test
    void updateReplayIs404WhenOwnershipMovedBeforeExecute() throws Exception {
        long vehicleId = createOk(owner1, newKey(), FIRST_REQUEST_ID, BODY_CREATE);
        String key = newKey();
        mockMvc.perform(update(owner1, vehicleId, key, FIRST_REQUEST_ID, BODY_UPDATE))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE t_vehicle SET owner_id = ? WHERE id = ?", owner2, vehicleId);

        MvcResult result = mockMvc.perform(update(owner1, vehicleId, key, SECOND_REQUEST_ID, BODY_UPDATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(header().doesNotExist(CommandResponses.HEADER_REPLAYED))
                .andReturn();
        evidence("MUST-VERIFY-2b-update-path-replay-after-ownership-moved-404", result);

        assertThat(raw(result)).as("不可见统一隐藏：旧 data 字段 0 命中")
                .doesNotContain("\"data\"").doesNotContain("vehicleId");
    }

    // ================================================================== 小工具

    private MockHttpServletRequestBuilder create(long actorId, String key, String requestId, String body) {
        MockHttpServletRequestBuilder builder = post("/api/v1/vehicles")
                .with(authentication(jwt(actorId, "ROLE_OWNER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            builder = builder.header(IdempotencyKey.HEADER_NAME, key);
        }
        if (requestId != null) {
            builder = builder.header(RequestIdFilter.HEADER_NAME, requestId);
        }
        return builder;
    }

    private MockHttpServletRequestBuilder update(long actorId, long vehicleId, String key,
                                                String requestId, String body) {
        MockHttpServletRequestBuilder builder = patch("/api/v1/vehicles/" + vehicleId)
                .with(authentication(jwt(actorId, "ROLE_OWNER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            builder = builder.header(IdempotencyKey.HEADER_NAME, key);
        }
        if (requestId != null) {
            builder = builder.header(RequestIdFilter.HEADER_NAME, requestId);
        }
        return builder;
    }

    private MockHttpServletRequestBuilder list(long actorId, String page, String pageSize) {
        MockHttpServletRequestBuilder builder = get("/api/v1/vehicles")
                .with(authentication(jwt(actorId, actorId == tech1 ? "ROLE_TECH" : "ROLE_OWNER")));
        if (page != null) {
            builder = builder.param("page", page);
        }
        if (pageSize != null) {
            builder = builder.param("pageSize", pageSize);
        }
        return builder;
    }

    /** 带上一个额外的 query 参数——用来验被禁止的筛选字段。 */
    private MockHttpServletRequestBuilder listWithExtra(long actorId, String name, String value) {
        return list(actorId, null, null).param(name, value);
    }

    /** 某个作用域下的幂等记录行数。 */
    private long idempotencyCount(long actorId, String operation, String key) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_idempotency WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
                Long.class, actorId, operation, key);
        return n == null ? 0 : n;
    }

    /**
     * 造一个<b>真的</b> {@link Jwt} 装进安全上下文。
     *
     * <p>为什么不能用 {@code @WithMockUser}：controller 取身份判的是 {@code principal instanceof Jwt}，
     * {@code @WithMockUser} 给的是 {@code User}，会直接 401。这里不需要真签名——
     * 验签是安全过滤链的活，controller 只读 {@code sub}。
     */
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

    /** 建一辆车并要求 201，返回它的 id。 */
    private long createOk(long actorId, String key, String requestId, String body) throws Exception {
        MvcResult result = mockMvc.perform(create(actorId, key, requestId, body))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(readString(result, "data", "vehicleId"));
    }

    private long currentVersion(long vehicleId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT row_version FROM t_vehicle WHERE id = ?", Long.class, vehicleId);
        return v == null ? -1 : v;
    }

    private long testVehicleCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_vehicle WHERE model_code = ?", Long.class, TEST_MODEL);
        return n == null ? 0 : n;
    }

    private long onlyTestVehicleId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_vehicle WHERE model_code = ?", Long.class, TEST_MODEL);
        return id == null ? 0 : id;
    }

    /** 库里 t_vehicle 的总行数——用来证明 total 不是全库总数。 */
    private long totalInDb() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_vehicle", Long.class);
        return n == null ? 0 : n;
    }

    private long userId(String loginName) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_user WHERE login_name = ?", Long.class, loginName);
        return id == null ? 0 : id;
    }

    private String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 去掉 requestId 之后的正文，用于「两种 404 是否同形」这类逐字比对。 */
    private String stripRequestId(MvcResult result) throws Exception {
        return raw(result).replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"<ignored>\"");
    }

    /** 按路径逐层取节点。用工程自己的 ObjectMapper 读树——本工程是 Jackson 3，第三方 JsonPath 对不上。 */
    private JsonNode node(MvcResult result, String... path) throws Exception {
        JsonNode current = objectMapper.readTree(raw(result));
        for (String step : path) {
            current = current.get(step);
        }
        return current;
    }

    private String readString(MvcResult result, String... path) throws Exception {
        return node(result, path).stringValue();
    }

    private long readLong(MvcResult result, String... path) throws Exception {
        return node(result, path).asLong();
    }

    private List<Long> readIds(MvcResult result) throws Exception {
        JsonNode items = node(result, "data", "items");
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.get("vehicleId").asLong());
        }
        return ids;
    }

    /**
     * 把一次响应的<b>状态行 ＋ 全部响应头 ＋ 正文</b>打一行纯 ASCII 的取证。
     *
     * <p>正文里的中文（错误 message、车牌占位值）按 {@code \\uXXXX} 转义：Windows 的 stdout
     * 是 GBK，中文直接进日志会变乱码——证据文件是要被拿去比对原文的，乱码等于没留。
     * 转义只换写法、不丢字符。头按名字排序，两次运行之间可比。
     */
    private void evidence(String what, MvcResult result) throws Exception {
        MockHttpServletRequest req = result.getRequest();
        StringBuilder sb = new StringBuilder();
        // 请求也要留：必验① 这类结论判的就是「请求里那个版本写成了什么」，只记响应等于半个结论
        // 只能采信自述。body 里的车牌原文照旧脱敏——取证不能把脱敏要求自己破坏掉。
        sb.append("req=").append(req.getMethod()).append(' ').append(req.getRequestURI());
        if (req.getQueryString() != null) {
            sb.append('?').append(req.getQueryString());
        }
        sb.append(" body=").append(redactPlate(requestBody(req)));
        sb.append(" | resp status=").append(result.getResponse().getStatus());
        sb.append(" headers={");
        List<String> names = new ArrayList<>(result.getResponse().getHeaderNames());
        Collections.sort(names);
        for (String name : names) {
            sb.append(name).append('=').append(result.getResponse().getHeaders(name)).append("; ");
        }
        sb.append("} body=").append(raw(result));
        System.out.println("[G1-02a] " + what + " = " + ascii(sb.toString()));
    }

    /** MockMvc 请求体；没有 body 时给空串（GET 就没有）。 */
    private static String requestBody(MockHttpServletRequest req) {
        try {
            return req.getContentAsString();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    /** 车牌原文不进证据：请求体里 {@code plateNumber} 的值一律换成占位符再打印。 */
    private static String redactPlate(String body) {
        return body == null ? "" : body.replaceAll("(\"plateNumber\"\\s*:\\s*)\"[^\"]*\"", "$1\"<redacted>\"");
    }

    /** 非 ASCII 一律写成 {@code \\uXXXX}，换行写 {@code \\n}。 */
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
