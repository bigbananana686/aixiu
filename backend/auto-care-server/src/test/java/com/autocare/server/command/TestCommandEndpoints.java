package com.autocare.server.command;

import com.autocare.server.web.ApiResponse;
import com.autocare.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * <b>test 作用域命令</b>：一条只存在于测试代码里的写接口，用来把幂等机制从 HTTP 那一头打通。
 *
 * <p><b>为什么它必须住在 {@code src/test}</b>：本片不实现任何业务接口（{@code createVehicle} 归 G1-02a）。
 * 要证「缺键 400」「重放响应头」这些 HTTP 层行为，就得有一条真路径——但那条路径<b>不能进生产代码</b>：
 * 仓库里会多出一个只为测试存在的假接口，被打进 fat jar，还可能被误当成真接口。放这里，它只在测试期存在。
 *
 * <p><b>注意类上没有 {@code @TestConfiguration}，也没有在任何地方写成 {@code @Bean}。</b>
 * {@code @SpringBootTest} 用的是主应用的 {@code @ComponentScan("com.autocare")}，
 * 而 {@code target/test-classes} 在测试期就在 classpath 上，所以这个 {@code @RestController}
 * 会被自动扫到并注册一次。再显式声明一次就会出现两份定义，启动直接报
 * {@code Ambiguous mapping}（G0-04 的 403 闸门那次实测踩过，见 {@code RoleGateTest} 的注释）。
 *
 * <p><b>它演示的就是 G1-02a 将来的接线写法</b>，一共三行实质内容：取身份 → 把两个函数递给执行器
 * → 用 {@code CommandResponses} 拼响应。业务层看不到任何幂等代码。
 *
 * <p><b>两个函数分别是哪一半</b>（见 {@link ReplayVisibility} 的表）：
 * <ul>
 *   <li>第三个参数 {@code () -> writer.insert(...)} —— <b>业务动作</b>，只在"这个键没见过"时跑；</li>
 *   <li>第四个参数 {@code TestSupport.vehicleVisibility(...)} —— <b>可见性复核</b>，
 *       只在"这个键见过、要重放"时跑，只查车还在不在、还是不是这位车主的。</li>
 * </ul>
 * 不写这个参数是<b>编译不过</b>的——这正是把口子设成必填参数的目的：接线的人必须回答
 * "这条命令的结果，怎么算还看得见"，而不是可以悄悄跳过。
 */
@RestController
class TestCommandEndpoints {

    private final CommandExecutor commandExecutor;
    private final TestVehicleWriter writer;
    private final JdbcTemplate jdbcTemplate;

    TestCommandEndpoints(CommandExecutor commandExecutor, TestVehicleWriter writer, JdbcTemplate jdbcTemplate) {
        this.commandExecutor = commandExecutor;
        this.writer = writer;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 等价于将来的 {@code POST /api/v1/vehicles}。
     *
     * <p>{@code required = false}：缺键不走 Spring 的 400，而是交给执行器抛——契约要的字段路径是
     * {@code /headers/Idempotency-Key}、子码 {@code REQUIRED}，那套形状只有执行器知道。
     */
    @PostMapping("/test/vehicles")
    ResponseEntity<ApiResponse<JsonNode>> create(
            @RequestBody TestVehicleRequest body,
            @RequestHeader(value = IdempotencyKey.HEADER_NAME, required = false) String idempotencyKey,
            HttpServletRequest request) {

        long actorId = TestSupport.currentActorId();

        return CommandResponses.toEntity(
                commandExecutor.execute(
                        CommandSpec.of("createVehicle"),
                        idempotencyKey,
                        body,
                        RequestIdFilter.currentRequestId(request),
                        // 未命中才跑：现在不执行，只是"递过去"。执行器决定它什么时候跑、在哪个事务里跑
                        () -> writer.insert(actorId, body),
                        // 命中重放才跑：只看车还在不在、owner_id 是不是他
                        TestSupport.vehicleVisibility(jdbcTemplate)),
                request);
    }
}
