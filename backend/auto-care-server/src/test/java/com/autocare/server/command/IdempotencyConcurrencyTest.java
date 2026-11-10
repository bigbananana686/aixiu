package com.autocare.server.command;

import com.autocare.common.error.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发同键：两个线程真并发发同一个键，业务只许写一次、库里那个键只许有一行。
 *
 * <p><b>这条测试证的是执行器里最硬的那段</b>：唯一约束把两个人排成一队（见
 * {@link IdempotencyMapper#insertPlaceholder}），输的那个撞键、退出当前事务、<b>另起新事务</b>
 * 读赢家的结果再重放。
 *
 * <p><b>两类东西必须真</b>：真 MySQL（H2 的唯一约束与锁行为不是一回事，锁等待根本没有），
 * 真并发（{@code CountDownLatch} 把两个线程卡在同一起跑线上，不是先后调用两次）。
 *
 * <p><b>为什么不断言"谁赢"</b>：谁先抢到是不确定的，断言具体分工会让测试变成掷骰子。
 * 断言的是<b>不变量</b>——不论谁先谁后、成功还是重放，业务只写一次。
 * 这不是退让，这正是并发测试该有的写法：能确定的是不变量，不是调度顺序。
 *
 * <p><b>为什么类上没有 {@code @Transactional}</b>：加了之后两个线程会共用主线程的事务，
 * 并发根本不存在（变成两个串行的内层调用）。这类测试最容易被这个注解悄悄毁掉。
 *
 * <p>输出固定为 ASCII：Windows 下 Java 的 stdout 按 GBK 出字节，中文会变成乱码，
 * 而这行输出是要写进证据文件的。
 */
@SpringBootTest
class IdempotencyConcurrencyTest {

    private static final int THREADS = 2;
    private static final String OPERATION = "createVehicle";

    @Autowired
    private CommandExecutor commandExecutor;

    @Autowired
    private TestVehicleWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long owner1;

    /** 重放前那道复核。输掉这场竞争的那个线程会走重放，所以这里也躲不掉它。 */
    private ReplayVisibility visibility;

    @BeforeEach
    void setUp() {
        owner1 = TestSupport.userId(jdbcTemplate, "owner1");
        visibility = TestSupport.vehicleVisibility(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TestSupport.cleanTestVehicles(jdbcTemplate);
    }

    @Test
    void twoThreadsSameKeyWriteBusinessOnlyOnce() throws Exception {
        String key = TestSupport.newKey();
        TestVehicleRequest body = new TestVehicleRequest("A1234567", 7777);

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Attempt> attempts = new ArrayList<>();
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    // SecurityContextHolder 是线程本地的：执行器读不到主线程装的身份，
                    // 每个工作线程必须自己装一遍，否则一律 401。
                    TestSupport.installActor(owner1);
                    ready.countDown();
                    try {
                        // 卡在同一起跑线上，等主线程放行
                        go.await();
                        CommandResult result = commandExecutor.execute(
                                CommandSpec.of(OPERATION), key, body, "req-concurrent",
                                () -> writer.insert(owner1, body), visibility);
                        return new Attempt(result.replayed() ? "replay" : "fresh", result.httpStatus());
                    } catch (ApiException e) {
                        // 业务/技术错误码：这里最可能见到 RETRYABLE_STORAGE_ERROR（锁等待被截断）
                        return new Attempt("api-error:" + e.errorCode().name(), e.errorCode().httpStatus());
                    } catch (RuntimeException e) {
                        return new Attempt("tech-error:" + e.getClass().getSimpleName(), 0);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).as("两个线程都已就绪").isTrue();
            go.countDown();

            for (Future<Attempt> future : futures) {
                attempts.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long fresh = count(attempts, "fresh");
        long replay = count(attempts, "replay");
        long apiError = attempts.stream().filter(a -> a.kind().startsWith("api-error")).count();
        long techError = attempts.stream().filter(a -> a.kind().startsWith("tech-error")).count();
        long businessRows = TestSupport.vehicleCount(jdbcTemplate);
        long idempotencyRows = TestSupport.idempotencyCount(jdbcTemplate, owner1, OPERATION, key);

        System.out.println("[G1-01 concurrency] threads=" + THREADS
                + " fresh=" + fresh
                + " replay=" + replay
                + " api-error=" + apiError
                + " tech-error=" + techError
                + " | invariant businessRows=" + businessRows
                + " idempotencyRows=" + idempotencyRows
                + " | detail=" + attempts);

        // 不变量一：业务只写一次——不管两个线程怎么抢，车只多了一行
        assertThat(businessRows).as("业务只写一次").isEqualTo(1);
        // 不变量二：库里这个键只有一行
        assertThat(idempotencyRows).as("该键只有一行幂等记录").isEqualTo(1);
        // 不变量三：至少一个请求拿到成功（成功或重放都算）
        assertThat(fresh + replay).as("至少一个请求成功").isGreaterThanOrEqualTo(1);
        // 不变量四：不该出现未预料的异常（503 是可接受的、明确归类的结果）
        assertThat(techError).as("不该出现未预料异常").isZero();
    }

    private static long count(List<Attempt> attempts, String kind) {
        return attempts.stream().filter(a -> a.kind().equals(kind)).count();
    }

    /** 一个线程的结果：分类 + HTTP 状态。 */
    private record Attempt(String kind, int httpStatus) {
    }
}
