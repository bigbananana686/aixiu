package com.autocare.server.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.autocare.server.web.RequestIdFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层：走<b>完整过滤器链</b>（编号 → 大小 → 键不支持 → 安全链 → 路由 → 执行器 → 全局异常处理器），
 * 用真的 MySQL。
 *
 * <p>这一册证五件事，都是"只在 HTTP 层才看得见"的：
 * <ol>
 *   <li>命令成功是 <b>201 + 相对 Location</b>；</li>
 *   <li>重放带 {@code Idempotency-Replayed: true} 与 {@code Original-Request-Id}，而且
 *       <b>响应头与信封里的 requestId 换成本次的</b>——原编号只出现在那个头里；</li>
 *   <li>缺键 / 非法键是 <b>400 信封</b>，字段路径 {@code /headers/Idempotency-Key}；</li>
 *   <li>405 带 {@code Allow} 头（C-19）；</li>
 *   <li><b>重放前的可见性复核（r2 / M-02）</b>：结果对象仍可见 → 照旧重放；已不可见 → <b>404 且不回旧 data</b>。
 *       这一条必须在这一册验，因为它要断言的是"响应体里有没有那些字段"与"有没有那两个重放头"，
 *       直调执行器只看得到一个异常对象，看不见响应长什么样。</li>
 * </ol>
 *
 * <p><b>请求体用 ASCII 车牌</b>：MockMvc 的 {@code content(String)} 会按请求的字符编码
 * （默认 ISO-8859-1）把字符串转成字节，中文会变成乱码。要么显式给 UTF-8 字节，要么用 ASCII。
 * 这里选 ASCII——本册要证的是幂等与响应形状，跟车牌是不是中文无关。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyHttpTest {

    private static final String FIRST_REQUEST_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND_REQUEST_ID = "22222222-2222-4222-8222-222222222222";

    /** 显式按 UTF-8 出字节，不经过 MockMvc 的字符串编码。 */
    private static final byte[] REQUEST_BODY =
            "{\"plateNumber\":\"A1234567\",\"mileage\":1234}".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long owner1;

    @BeforeEach
    void setUp() {
        owner1 = TestSupport.userId(jdbcTemplate, "owner1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TestSupport.cleanTestVehicles(jdbcTemplate);
    }

    // ------------------------------------------------------------------ 1. 201 + Location

    @Test
    void createReturns201WithRelativeLocation() throws Exception {
        mockMvc.perform(create(TestSupport.newKey(), FIRST_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                // 相对路径，不是绝对地址
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/vehicles/")))
                // 响应头与信封里的编号一致，都取自编号过滤器
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, FIRST_REQUEST_ID))
                .andExpect(jsonPath("$.requestId").value(FIRST_REQUEST_ID))
                .andExpect(jsonPath("$.data.vehicleId").isNotEmpty())
                .andExpect(jsonPath("$.data.modelCode").value(TestVehicleWriter.TEST_MODEL))
                // 私有数据不许被缓存
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 2. 重放响应头

    /**
     * 第二次同键同内容：原状态、原 data，加两个头，且<b>信封里的 requestId 是本次的</b>。
     *
     * <p>这一条是契约那句话的落地：「重放回传原 HTTP 状态、Location 与 data，并加
     * {@code Idempotency-Replayed: true}、{@code Original-Request-Id}；原 response 含原 requestId
     * 用于审计，传输时替换为本次有效 requestId」。
     */
    @Test
    void replayReturnsOriginalResultWithReplayHeadersAndFreshRequestId() throws Exception {
        String key = TestSupport.newKey();

        mockMvc.perform(create(key, FIRST_REQUEST_ID)).andExpect(status().isCreated());

        mockMvc.perform(create(key, SECOND_REQUEST_ID))
                // 原状态：还是 201，不是 200
                .andExpect(status().isCreated())
                .andExpect(header().string(CommandResponses.HEADER_REPLAYED, "true"))
                .andExpect(header().string(CommandResponses.HEADER_ORIGINAL_REQUEST_ID, FIRST_REQUEST_ID))
                // 编号过滤器照常发本次的编号：头是本次的
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, SECOND_REQUEST_ID))
                // 信封里也是本次的——原编号只在上面那个头里
                .andExpect(jsonPath("$.requestId").value(SECOND_REQUEST_ID))
                .andExpect(jsonPath("$.data.vehicleId").isNotEmpty());

        // 重放没有再写一行
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. 缺键 / 非法键

    @Test
    void missingKeyIsRejectedAsEnvelope400() throws Exception {
        mockMvc.perform(post("/test/vehicles")
                        .with(authentication(TestSupport.jwtAuthentication(owner1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(RequestIdFilter.HEADER_NAME, FIRST_REQUEST_ID)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/headers/Idempotency-Key"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("REQUIRED"));

        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isZero();
    }

    @Test
    void malformedKeyIsRejectedAsEnvelope400() throws Exception {
        mockMvc.perform(create("short", FIRST_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("/headers/Idempotency-Key"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("INVALID_FORMAT"));

        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isZero();
    }

    // ------------------------------------------------------------------ 4. C-19：405 带 Allow

    /**
     * 方法不对：405 + {@code Allow} 头，值是<b>该路径真正支持的方法</b>（问异常要，不写死）。
     *
     * <p>{@code /api/v1/me} 只声明了 GET，所以 Allow 里应当有 GET、不该有 PUT。
     * 用 {@code containsString} 而不是等值断言：将来给它加个 HEAD 或 OPTIONS 也不该让这条测试误报。
     */
    @Test
    void methodNotAllowedCarriesAllowHeader() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .with(authentication(TestSupport.jwtAuthentication(owner1))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                // 仍然是项目信封，业务码沿用 INVALID_ARGUMENT（§4 目录封闭，不新增 405 码）
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    // ------------------------------------------------------------------ 5. M-02：重放前的可见性复核

    /**
     * ① 结果对象<b>仍然可见</b> → 照旧重放：原状态、原 Location、原 data ＋ 两个头。
     *
     * <p><b>这条不是把上面第 2 条抄一遍，它多考一件事：复核到底在查什么。</b>
     * 第一次请求建完车之后，我们把车这一行<b>就地改掉</b>——里程从 1234 换成 99999，
     * {@code row_version} 顶到 2。归属一个字没动。
     *
     * <p>于是这条断言能钉死两件事：
     * <ul>
     *   <li>复核<b>只认归属</b>。它要是顺手把 {@code row_version} 也算进去，这次重放就会被判成
     *       "不可见"而回 404——那是把动态业务检查混进复核的典型症状；</li>
     *   <li>重放返回的是<b>当初那一份</b>结果，不是拿库里的现值重算。所以里程还是 1234、
     *       {@code rowVersion} 还是 {@code "1"}，而库里已经不是了。契约那句「重放保留原业务结果
     *       （可能是历史状态），前端再 GET 当前详情」就是这个意思。</li>
     * </ul>
     */
    @Test
    void replayStillReturnsStoredResultWhenResultObjectIsStillVisible() throws Exception {
        String key = TestSupport.newKey();

        mockMvc.perform(create(key, FIRST_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mileage").value(1234))
                .andExpect(jsonPath("$.data.rowVersion").value("1"));

        long vehicleId = TestSupport.onlyVehicleId(jdbcTemplate);
        jdbcTemplate.update("UPDATE t_vehicle SET mileage = 99999, row_version = 2 WHERE id = ?", vehicleId);

        MvcResult replay = mockMvc.perform(create(key, SECOND_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(header().string(CommandResponses.HEADER_REPLAYED, "true"))
                .andExpect(header().string(CommandResponses.HEADER_ORIGINAL_REQUEST_ID, FIRST_REQUEST_ID))
                // 老结果原样：库里已经是 99999 / rowVersion 2，还回去的仍是当初那一份
                .andExpect(jsonPath("$.data.mileage").value(1234))
                .andExpect(jsonPath("$.data.rowVersion").value("1"))
                .andReturn();
        evidence("A still-visible", replay);

        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
    }

    /**
     * ② 结果对象<b>对本人已不可见</b> → 404，且一个字的旧 data 都不返回。
     *
     * <p>做法是把那一行的归属改到别人名下。刻意选"改归属"而不是"删掉那一行"：
     * 这一行还在、内容也一个字没变，变的只有"它属不属于他"。所以这条测试能证明
     * 复核看的确实是归属，而不是"资源存不存在"——后者用一句 SELECT 判空就够了，
     * 前者才要求带上 actor 一起筛。
     *
     * <p>要钉死的三件事：<b>404 而不是 409 或 200</b>；响应体里<b>旧 data 的字段一个都不许出现</b>
     * （那份 data 装的正是现在不该给他看的对象，还回去就等于把 404 该藏的东西漏出去）；
     * 以及<b>没有任何新增业务写入</b>，幂等记录也没被这次失败改写。
     */
    @Test
    void replayIsHiddenAs404WhenResultObjectIsNoLongerVisible() throws Exception {
        String key = TestSupport.newKey();

        mockMvc.perform(create(key, FIRST_REQUEST_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.vehicleId").isNotEmpty());

        long vehicleId = TestSupport.onlyVehicleId(jdbcTemplate);
        long owner2 = TestSupport.userId(jdbcTemplate, "owner2");
        jdbcTemplate.update("UPDATE t_vehicle SET owner_id = ? WHERE id = ?", owner2, vehicleId);

        MvcResult result = mockMvc.perform(create(key, SECOND_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                // 编号照旧是本次的：说明这条 404 走的是普通错误出口，不是漏成了别的什么东西
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, SECOND_REQUEST_ID))
                // 它压根没重放成功，所以那两个重放头不许出现
                .andExpect(header().doesNotExist(CommandResponses.HEADER_REPLAYED))
                .andExpect(header().doesNotExist(CommandResponses.HEADER_ORIGINAL_REQUEST_ID))
                .andReturn();
        evidence("B no-longer-visible", result);

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .as("不可见统一隐藏：旧 data 的字段一个都不许出现")
                .doesNotContain("\"data\"")
                .doesNotContain("vehicleId")
                .doesNotContain("plateMasked")
                .doesNotContain("rowVersion");

        // 失败的那次没有新增业务写入（那一行还在，只是换了主人）
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
        // 幂等记录仍在，且没被这次失败改写——失败不缓存、也不删成功记录
        assertThat(TestSupport.idempotencyCount(jdbcTemplate, owner1, "createVehicle", key)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 把一次响应的<b>状态行 ＋ 全部响应头 ＋ 正文</b>打一行纯 ASCII 的取证。
     *
     * <p>为什么要自己打一遍，而不是"断言过了就算"：验收要看的是<b>原始报文</b>——
     * 「404 的响应体里到底有没有出现旧 data 的字段」这件事，只有把报文摊开来看才算数，
     * 光看一句"断言通过"等于采信自述。头按名字排序，两次运行之间可比。
     *
     * <p>正文交给 {@link TestSupport#ascii} 转义：错误 message 是中文，直接进 Windows 的
     * GBK stdout 会变乱码，那样留了也白留。
     */
    private static void evidence(String what, MvcResult result) throws Exception {
        StringBuilder sb = new StringBuilder("status=").append(result.getResponse().getStatus());
        sb.append(" headers={");
        List<String> names = new ArrayList<>(result.getResponse().getHeaderNames());
        Collections.sort(names);
        for (String name : names) {
            sb.append(name).append('=').append(result.getResponse().getHeaders(name)).append("; ");
        }
        sb.append("} body=").append(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        System.out.println("[G1-01r2 replayvisibility] " + what + " = " + TestSupport.ascii(sb.toString()));
    }

    private MockHttpServletRequestBuilder create(String key, String requestId) {
        return post("/test/vehicles")
                .with(authentication(TestSupport.jwtAuthentication(owner1)))
                .contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyKey.HEADER_NAME, key)
                .header(RequestIdFilter.HEADER_NAME, requestId)
                .content(REQUEST_BODY);
    }
}
