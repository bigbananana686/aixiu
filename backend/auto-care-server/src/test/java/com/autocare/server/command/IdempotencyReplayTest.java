package com.autocare.server.command;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等机制本体（直接调执行器，不走 HTTP）。
 *
 * <p>覆盖六类里的五类：<b>同键同内容重放、同键异摘要 409、失败回滚后可重试、作用域隔离、缺键/非法键</b>。
 * 并发那一类在 {@link IdempotencyConcurrencyTest}，HTTP 层那几项在 {@link IdempotencyHttpTest}。
 *
 * <p><b>为什么机制用直调、HTTP 另开一册</b>：失败时排查范围小一半。这里挂了只可能是机制的问题；
 * HTTP 那边挂了只可能是过滤器链或响应拼装的问题。混在一起写，一次失败要同时怀疑两件事。
 *
 * <p><b>类上没有 {@code @Transactional}，这是刻意的。</b>加了之后整个测试方法被包在一笔外层事务里，
 * 执行器自己开的那些事务会变成内层，回滚和提交的语义全变——"失败回滚"和"并发"两类就都测不出来了。
 * 测试数据靠 {@code @AfterEach} 显式清理。
 */
@SpringBootTest
class IdempotencyReplayTest {

    private static final String OPERATION = "createVehicle";
    private static final String FIRST_REQUEST_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND_REQUEST_ID = "22222222-2222-4222-8222-222222222222";

    @Autowired
    private CommandExecutor commandExecutor;

    @Autowired
    private TestVehicleWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long owner1;
    private long owner2;

    /**
     * 重放前要过的那道复核。执行器把它设成必填参数——漏了就编译不过，
     * 所以这里每一处 {@code execute} 都显式带着它，不是可选项。
     */
    private ReplayVisibility visibility;

    @BeforeEach
    void setUp() {
        owner1 = TestSupport.userId(jdbcTemplate, "owner1");
        owner2 = TestSupport.userId(jdbcTemplate, "owner2");
        visibility = TestSupport.vehicleVisibility(jdbcTemplate);
        TestSupport.installActor(owner1);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TestSupport.cleanTestVehicles(jdbcTemplate);
    }

    // ------------------------------------------------------------------ 1. 新键第一次执行

    /** 第一次：真执行，201，Location 指向新资源，库里落一行。 */
    @Test
    void firstCallExecutesAndStoresOneVehicle() {
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);

        CommandResult result = execute(TestSupport.newKey(), body, FIRST_REQUEST_ID);

        assertThat(result.replayed()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.location()).startsWith("/api/v1/vehicles/");
        assertThat(result.data().get("modelCode").stringValue()).isEqualTo(TestVehicleWriter.TEST_MODEL);
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 2. 同键同内容 → 重放

    /**
     * 同一把键、同样的内容，再来一遍：重放，业务只落一条。
     *
     * <p>这条对应契约那个"响应在网络上丢了"的场景——客户端重试，服务端把<b>原结果</b>还回去，
     * 而不是再写一遍。断言里的 {@code vehicleCount == 1} 是关键：第二次的业务 lambda
     * 压根没被执行（它要是跑了就会插第二行）。
     */
    @Test
    void sameKeySameContentReplaysWithoutSecondWrite() {
        String key = TestSupport.newKey();
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);

        CommandResult first = execute(key, body, FIRST_REQUEST_ID);
        CommandResult second = execute(key, body, SECOND_REQUEST_ID);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        // 原状态、原 Location、原 data——三样都得跟第一次一致
        assertThat(second.httpStatus()).isEqualTo(first.httpStatus());
        assertThat(second.location()).isEqualTo(first.location());
        assertThat(second.data()).isEqualTo(first.data());
        // 原 requestId 走 Original-Request-Id（这里先验值，头在 HTTP 那册验）
        assertThat(second.originalRequestId()).isEqualTo(FIRST_REQUEST_ID);
        // 业务只落一条
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. 同键异内容 → 409

    /**
     * 同一把键、内容变了：409 {@code IDEMPOTENCY_KEY_REUSED}，不能拿旧结果糊弄。
     *
     * <p>只改了一个字段（mileage 1234 → 9999），摘要就不同了。这就是"数组顺序参与摘要""完整
     * 已校验 body 参与摘要"那些规则的实际效果：改一个字节，就是另一次意图。
     */
    @Test
    void sameKeyDifferentContentIsRejectedWithConflictSubcode() {
        String key = TestSupport.newKey();
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);
        TestVehicleRequest changed = new TestVehicleRequest("沪A12345", 9999);

        execute(key, body, FIRST_REQUEST_ID);

        ApiException thrown = null;
        try {
            execute(key, changed, SECOND_REQUEST_ID);
        } catch (ApiException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull();
        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(thrown.fieldErrors()).hasSize(1);
        assertThat(thrown.fieldErrors().get(0).field()).isEqualTo("/headers/Idempotency-Key");
        assertThat(thrown.fieldErrors().get(0).code()).isEqualTo(FieldErrorCode.CONFLICT);
        // 被拒绝的那次一个字节都没写
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 4. 失败回滚 + 同键可重试

    /**
     * 失败必须连同幂等记录一起回滚，同一个键还能再用。
     *
     * <p>这里故意让业务动作<b>先真写一行再抛异常</b>——这样能一次证明两件事：
     * 业务写入没了（车那一行），幂等记录也没了（{@code t_idempotency} 零行）。
     * 要是幂等记录留了下来，下一次同键重试就会命中它，客户端会拿到一个<b>假的</b>成功。
     */
    @Test
    void failureRollsBackBothVehicleAndIdempotencyRowThenSameKeySucceeds() {
        String key = TestSupport.newKey();
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);

        ApiException failure = null;
        try {
            commandExecutor.execute(CommandSpec.of(OPERATION), key, body, "req-fail", () -> {
                writer.insert(owner1, body);
                throw ApiException.of(ErrorCode.SLOT_CONFLICT);
            }, visibility);
        } catch (ApiException e) {
            failure = e;
        }

        assertThat(failure).isNotNull();
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.SLOT_CONFLICT);
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isZero();
        assertThat(TestSupport.idempotencyCount(jdbcTemplate, owner1, OPERATION, key)).isZero();

        // 同一把键、同样的内容重试 → 这次能成功
        CommandResult retry = execute(key, body, "req-retry");
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.httpStatus()).isEqualTo(201);
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(1);
        assertThat(TestSupport.idempotencyCount(jdbcTemplate, owner1, OPERATION, key)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 5. 作用域隔离

    /**
     * 同键、不同 operation → 互不影响。
     *
     * <p>证明作用域里的「哪件事」那一格真的在起作用。要是作用域只由键决定，
     * 第二次会被拦成 409；这里是两条独立的意图，各自执行。
     */
    @Test
    void sameKeyDifferentOperationDoesNotCollide() {
        String key = TestSupport.newKey();
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);

        CommandResult a = execute(OPERATION, key, body, "req-a");
        CommandResult b = execute("updateVehicle", key, body, "req-b");

        assertThat(a.replayed()).isFalse();
        assertThat(b.replayed()).isFalse();
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(2);
    }

    /**
     * 同键、同 operation、不同 actor → 互不影响。
     *
     * <p>证明作用域里的「谁」那一格来自认证身份，而不是别的地方。两个车主的键撞在一起
     * 不该互相干扰——键是客户端自己编的，两个人撞键完全可能。
     */
    @Test
    void sameKeyDifferentActorDoesNotCollide() {
        String key = TestSupport.newKey();
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);

        CommandResult a = execute(key, body, "req-a");
        assertThat(a.replayed()).isFalse();

        TestSupport.installActor(owner2);
        CommandResult b = execute(key, body, "req-b");

        assertThat(b.replayed()).isFalse();
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 6. 缺键 / 非法键

    /** 缺键：400 + 字段路径 {@code /headers/Idempotency-Key} + 子码 {@code REQUIRED}。 */
    @Test
    void missingKeyIsRejectedWithRequiredSubcode() {
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);

        ApiException thrown = null;
        try {
            commandExecutor.execute(CommandSpec.of(OPERATION), null, body, FIRST_REQUEST_ID,
                    () -> writer.insert(owner1, body), visibility);
        } catch (ApiException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull();
        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(thrown.fieldErrors()).hasSize(1);
        assertThat(thrown.fieldErrors().get(0).field()).isEqualTo("/headers/Idempotency-Key");
        assertThat(thrown.fieldErrors().get(0).code()).isEqualTo(FieldErrorCode.REQUIRED);
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isZero();
    }

    /**
     * 非法键：同样 400，但子码是 {@code INVALID_FORMAT}。
     *
     * <p>试了三种非法形状，因为它们的失败原因各不相同：太短、首字符不是字母数字、含非法字符。
     * 它们必须都落在同一个子码上——契约的字段级子码是封闭表，这里只有 {@code INVALID_FORMAT} 一个答案。
     */
    @Test
    void malformedKeysAreRejectedWithFormatSubcode() {
        TestVehicleRequest body = new TestVehicleRequest("沪A12345", 1234);

        for (String bad : new String[]{"short", "-startsWithDash1234567890", "has space 0123456789ab"}) {
            ApiException thrown = null;
            try {
                commandExecutor.execute(CommandSpec.of(OPERATION), bad, body, FIRST_REQUEST_ID,
                        () -> writer.insert(owner1, body), visibility);
            } catch (ApiException e) {
                thrown = e;
            }
            assertThat(thrown).as("非法键 %s 应被拒绝", bad).isNotNull();
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
            assertThat(thrown.fieldErrors().get(0).code()).isEqualTo(FieldErrorCode.INVALID_FORMAT);
        }
        assertThat(TestSupport.vehicleCount(jdbcTemplate)).isZero();
    }

    // ------------------------------------------------------------------ 小工具

    private CommandResult execute(String key, TestVehicleRequest body, String requestId) {
        return execute(OPERATION, key, body, requestId);
    }

    private CommandResult execute(String operation, String key, TestVehicleRequest body, String requestId) {
        return commandExecutor.execute(CommandSpec.of(operation), key, body, requestId,
                () -> writer.insert(owner1, body), visibility);
    }
}
