package com.autocare.server.command;

import com.autocare.common.identity.Role;
import com.autocare.server.security.JwtTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1-01 r2 / M-01：把「同键并发等锁超 5 秒 → 503 {@code RETRYABLE_STORAGE_ERROR}」这条路径<b>真的走一遍</b>。
 *
 * <p><b>为什么这条要单独补证。</b>r1 的并发用例走的是「撞键即重读」快路径——两个线程抢同一个键，
 * 输的那位在赢家提交后拿到重复键错误、退出事务重读，全程几毫秒。于是
 * {@code application-example.yml} 里那句 {@code connection-init-sql: SET SESSION innodb_lock_wait_timeout = 5}
 * 和 {@code CommandExecutor} 里那个 {@code PessimisticLockingFailureException → 503} 分支，
 * <b>一行证据都没有</b>：没人知道它到底生不生效。真竞争下不生效的后果是客户端挂 50 秒，
 * 或者异常没被认出来、被兜成 500——两件都必须在机制片里钉死。
 *
 * <p><b>怎么把「等 5 秒」造出来。</b>幂等键的串行化点是 {@code insertPlaceholder} 那句 INSERT
 * ——它插的那一行<b>在事务里还没提交</b>，唯一索引 {@code uk_idem_scope} 上就压着一把锁。
 * 所以只要让第一个请求在插完占位行之后<b>迟迟不提交</b>，第二个请求的那句 INSERT 就会真的去等。
 * 这里的做法是业务动作里写完业务行之后 {@code Thread.sleep} 一段再返回——sleep 只落在
 * {@code src/test} 里，生产代码一行都没动。
 *
 * <p><b>两条路各用各的证据源，这是刻意的。</b>
 * <ul>
 *   <li>第一个请求（按住锁的那个）<b>直调执行器</b>：要的就是「事务开着、占位行已插」这个状态，
 *       而且它的 {@code requestId} 由我们给定，后面才能拿它核对 {@code Original-Request-Id}。</li>
 *   <li>第二个请求<b>走真 HTTP</b>（随机端口 + 内嵌 Tomcat + 完整过滤器链 + 真 socket）：
 *       要证的东西里有一半只在 HTTP 这一层看得见——503 状态行、{@code Retry-After: 1} 响应头、
 *       以及<b>实际耗时</b>。直调执行器只能拿到一个异常对象，拿不到这些。</li>
 * </ul>
 *
 * <p><b>顺序靠 latch 钉死，不靠 sleep 猜。</b>只有第一个请求在事务里把 latch 放开了，
 * 才发第二个请求——那一刻「占位行已插、事务未提交」是确定的。要是靠"发完第一个就等一会儿
 * 再发第二个"，这条测试就成了骰子：抢在前面的话第二个会先插进去，它变成赢家，
 * 被截断的反而成了第一个，而断言是写在第二个身上的。
 *
 * <p><b>输出固定为 ASCII。</b>Windows 下 Java 的 stdout 按 GBK 出字节，中文会变成乱码，
 * 而这些行是要原样落进证据文件的。响应体里那句中文错误消息由 {@link TestSupport#ascii} 转义，
 * 内容照样是真的，只是换了个不会被编码毁掉的写法。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LockWaitTimeoutTest {

    private static final String OPERATION = "createVehicle";

    /**
     * 第一个请求在事务里停多久。契约的等待上限是 5 秒，这里给 12 秒：
     * 第二个请求在 ~5 秒被截断，之后还剩 ~7 秒的窗口，够主线程从容地去数一次行数
     * （那一次「提交前数是 0」是「失败不留痕迹」的证据）。给 8 秒就只剩 3 秒窗口，太挤。
     */
    private static final int HOLD_MILLIS = 12000;

    /** 第一个请求的 requestId，写死是为了待会儿核对重放的 {@code Original-Request-Id}。 */
    private static final String HOLD_REQUEST_ID = "hold-1111-4111-8111-111111111111";

    private static final String PLATE = "A1234567";
    private static final int MILEAGE = 7777;

    @Autowired
    private CommandExecutor commandExecutor;

    @Autowired
    private TestVehicleWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenIssuer tokenIssuer;

    @Autowired
    private Environment environment;

    private HttpClient http;
    private String baseUrl;
    private String token;
    private long owner1;

    /** 重放前那道复核。第二个请求走 HTTP，用到的是 TestCommandEndpoints 里配好的那一个。 */
    private ReplayVisibility visibility;

    @BeforeEach
    void setUp() {
        owner1 = TestSupport.userId(jdbcTemplate, "owner1");
        visibility = TestSupport.vehicleVisibility(jdbcTemplate);
        // 真 socket、真 Tomcat：端口由 webEnvironment=RANDOM_PORT 随机分配，
        // 从环境里读回来（不注入 @LocalServerPort，少一个随 Boot 版本搬家的注解依赖）。
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        // 令牌用生产那条签发路径真签，不走测试专用旁路：这样第二个请求经过的过滤器链
        // 与线上完全一致（HS256 白名单、iss/aud/exp 校验一个不少）。
        token = tokenIssuer.issue(owner1, Role.OWNER, Instant.now());
        // 超时给到 60 秒，刻意大于 MySQL 默认的 50 秒——万一配置没生效，这条会如实挂到 50 秒
        // 并被下面的耗时断言抓住，而不是被客户端超时掩盖成一个看不出原因的失败。
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TestSupport.cleanTestVehicles(jdbcTemplate);
    }

    // ------------------------------------------------------------------ ① 配置真的生效了吗

    /**
     * 应用连接内的会话值必须是 5。
     *
     * <p>为什么不能只看 yml：那是「我们写了什么」，不是「数据库认了什么」。本项目对数据库结论
     * 有一条硬要求——<b>取应用连接内的会话值</b>。这里的 {@code JdbcTemplate} 用的就是应用自己的
     * Hikari 池，所以它手里这条连接与跑业务的那条是同一套初始化。
     *
     * <p>顺带把<b>服务端全局值</b>也打出来：如果两边都是 5，那就说明不了什么（可能是服务器本来就配了
     * 5）；只有「全局是默认的 50、会话是 5」才证明这 5 是 {@code connection-init-sql} 设上去的。
     * 全局值只打印、不断言——它是环境属性，不该让测试去管住它。
     */
    @Test
    void applicationConnectionSeesFiveSecondLockWaitTimeout() {
        Integer sessionValue = jdbcTemplate.queryForObject("SELECT @@innodb_lock_wait_timeout", Integer.class);
        Integer globalValue = jdbcTemplate.queryForObject("SELECT @@global.innodb_lock_wait_timeout", Integer.class);

        line("app-connection session innodb_lock_wait_timeout", String.valueOf(sessionValue));
        line("server-side global innodb_lock_wait_timeout", String.valueOf(globalValue));

        assertThat(sessionValue).as("应用连接内的会话锁等待上限").isEqualTo(5);
    }

    // ------------------------------------------------------------------ ② 这条路径真的会被走到吗

    /**
     * 同键并发、第一个请求抱着锁不放：第二个请求必须在<b>约 5 秒</b>拿到
     * 503 {@code RETRYABLE_STORAGE_ERROR} ＋ {@code Retry-After: 1}，而且不留下任何痕迹。
     *
     * <p>四条断言，一条比一条具体：
     * <ol>
     *   <li><b>状态与错误码</b>：503 + {@code RETRYABLE_STORAGE_ERROR} + {@code Retry-After: 1}
     *       ——这是契约 §5.3 第 4 条那句话的字面形状；</li>
     *   <li><b>实际耗时落在 5 秒量级</b>：上界卡在 15 秒，不是 50 秒。这条是「配置生效」的间接证据
     *       ——没生效就会挂到 MySQL 默认的 50 秒；</li>
     *   <li><b>失败那次不留幂等记录</b>：在第一个请求提交之前去数这个作用域的行数，应当是 0
     *       （第二个请求的事务已整体回滚；第一个请求那行还没提交，别的连接看不见）；</li>
     *   <li><b>等第一个提交后同键重试能成功，且业务只落一条</b>。</li>
     * </ol>
     */
    @Test
    void secondRequestOnSameKeyIsCutOffAfterFiveSecondLockWait() throws Exception {
        String key = TestSupport.newKey();
        TestVehicleRequest body = new TestVehicleRequest(PLATE, MILEAGE);

        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CommandResult> first = pool.submit(() -> {
                // SecurityContextHolder 是线程本地的：工作线程得自己装一遍身份，否则执行器一律 401
                TestSupport.installActor(owner1);
                try {
                    return commandExecutor.execute(CommandSpec.of(OPERATION), key, body, HOLD_REQUEST_ID, () -> {
                        // 先把业务那一行写进去（它此时也只在事务里，外面看不见），
                        // 再通知主线程、再停住。顺序这样定是为了让主线程待会儿能查到
                        // 「业务行数是 0」——那不是"还没写"，是"写了但没提交，别的连接看不见"。
                        CommandOutcome<Map<String, Object>> written = writer.insert(owner1, body);
                        // ★ 走到这里说明幂等占位行与业务行都已插进事务（锁已持有），才放行主线程去发第二个请求。
                        //   测试专用：生产代码里没有任何 sleep。
                        lockHeld.countDown();
                        sleepQuietly(HOLD_MILLIS);
                        return written;
                    }, visibility);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            assertThat(lockHeld.await(30, TimeUnit.SECONDS))
                    .as("第一个请求应已进入事务并持有该幂等键").isTrue();

            // 第二个请求：同一把键、同样的内容，真 HTTP 打进来
            Instant startedAt = Instant.now();
            HttpResponse<String> second = post(key, PLATE, MILEAGE);
            long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();

            line("B raw response", "status=" + second.statusCode()
                    + " headers=" + headersOf(second) + " body=" + second.body());
            line("B elapsedMs", String.valueOf(elapsedMs));

            // ① 状态、错误码、重试提示
            assertThat(second.statusCode()).as("第二个请求应当是 503").isEqualTo(503);
            assertThat(second.body()).as("错误码").contains("RETRYABLE_STORAGE_ERROR");
            assertThat(second.headers().firstValue("Retry-After")).as("Retry-After").hasValue("1");
            // 信封形状：不能让失败响应缺 requestId / error.retryable
            assertThat(second.body()).contains("\"retryable\":true");
            assertThat(second.body()).doesNotContain("\"data\"");

            // ② 量级是 5 秒，不是 50 秒
            assertThat(elapsedMs)
                    .as("等待被截断在 5 秒量级（配 50 秒默认值的话这里会是 50000 上下）")
                    .isBetween(4000L, 15_000L);

            // ③ 失败那次不留任何东西：此刻第一个请求还没提交，第二个已经整体回滚
            long midIdempotencyRows = TestSupport.idempotencyCount(jdbcTemplate, owner1, OPERATION, key);
            long midBusinessRows = TestSupport.vehicleCount(jdbcTemplate);
            line("mid-window (A still holding the lock)",
                    "idempotencyRows=" + midIdempotencyRows + " businessRows=" + midBusinessRows);
            assertThat(midIdempotencyRows).as("失败那次不该留下幂等记录").isZero();
            assertThat(midBusinessRows).as("当前可见的业务行数（A 还没提交）").isZero();

            // ④ 第一个请求放完锁、正常提交
            CommandResult held = first.get(60, TimeUnit.SECONDS);
            line("A finished", "status=" + held.httpStatus() + " replayed=" + held.replayed());
            assertThat(held.httpStatus()).as("第一个请求自己应当成功").isEqualTo(201);
            assertThat(held.replayed()).isFalse();

            long afterIdempotencyRows = TestSupport.idempotencyCount(jdbcTemplate, owner1, OPERATION, key);
            long afterBusinessRows = TestSupport.vehicleCount(jdbcTemplate);
            line("after-commit", "idempotencyRows=" + afterIdempotencyRows
                    + " businessRows=" + afterBusinessRows);
            assertThat(afterIdempotencyRows).as("该键最终只有一行").isEqualTo(1);
            assertThat(afterBusinessRows).as("业务只落一条").isEqualTo(1);

            // ⑤ 同一个键再打一次：这时才命中成功记录，正常重放
            HttpResponse<String> retry = post(key, PLATE, MILEAGE);
            line("retry raw response", "status=" + retry.statusCode()
                    + " headers=" + headersOf(retry) + " body=" + retry.body());
            assertThat(retry.statusCode()).as("同键重试应当重放成功").isEqualTo(201);
            assertThat(retry.headers().firstValue(CommandResponses.HEADER_REPLAYED)).hasValue("true");
            assertThat(retry.headers().firstValue(CommandResponses.HEADER_ORIGINAL_REQUEST_ID))
                    .as("原请求编号应该是第一个请求的那个")
                    .hasValue(HOLD_REQUEST_ID);
            assertThat(TestSupport.vehicleCount(jdbcTemplate)).as("重放不再写库").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 小工具

    private HttpResponse<String> post(String key, String plate, int mileage) throws Exception {
        String json = "{\"plateNumber\":\"" + plate + "\",\"mileage\":" + mileage + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/test/vehicles"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header(IdempotencyKey.HEADER_NAME, key)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试用的停等被打断", e);
        }
    }

    /** 一行证据。前缀固定，取证脚本按它从测试日志里整段捞出来。 */
    private static void line(String what, String value) {
        System.out.println("[G1-01r2 lockwait] " + what + " = " + TestSupport.ascii(value));
    }

    private static String headersOf(HttpResponse<String> response) {
        StringBuilder sb = new StringBuilder("{");
        response.headers().map().entrySet().stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append("; "));
        return sb.append('}').toString();
    }
}
