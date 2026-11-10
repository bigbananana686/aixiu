package com.autocare.server.command;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.server.web.ApiResponse;
import com.autocare.server.web.UtcTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * 统一命令入口：幂等机制的落点。所有 {@code REQUIRED_SUCCESS_REPLAY} 的写命令都从这里过。
 *
 * <p><b>它在契约 §5.3 强制次序里的位置</b>——前三步由更外层完成，本类从第 2 步的后半开始：
 * <ol>
 *   <li>认证 / 角色 → 基础语法与字段 → 引用对象权限：<b>调用方负责</b>，必须已经做完。</li>
 *   <li>算摘要、查成功记录；命中后<b>复核结果对象当前可见</b>，通过就重放、不可见就 404
 *       （或摘要不同 409），这一切<b>先于任何动态业务检查</b>：本类。</li>
 *   <li>未命中才开新短事务登记键 → 取业务锁 → 锁内复核 → 业务写入与成功响应同事务提交：本类。</li>
 * </ol>
 *
 * <p><b>调度方（controller）必须守的两条规矩</b>——这是本方案的结构性约束，本片没有真实业务接口，
 * 所以<b>编译器管不了、测试也测不出来</b>，只能靠这条注释 + 后面接线时逐个核对：
 * <ol>
 *   <li><b>动态检查一律留在 lambda 里。</b>「订单还 READY 吗」「报价过期没」「目录还上架吗」
 *       这类判断，如果写在 {@code execute(...)} 之前，就会跑在重放前面——契约明说历史成功重放
 *       不执行这些检查，写反了会让一个早该被重放成功的请求变成 409。</li>
 *   <li><b>引用 / 归属检查必须在 {@code execute(...)} 之前做完，而且只许查"存在与归属"。</b>
 *       契约把「这个订单是不是我的」排在重放之前（失败要 404）。它既不能塞进 lambda
 *       （那样会跑到重放后面），也不能顺手多查状态与版本（那是第 3 步的活）。</li>
 * </ol>
 * <p>换句话说：{@code execute} 之前只做搬运和"存不存在、是不是你的"；{@code execute} 之后（lambda 里）
 * 才做"现在能不能"。中间那条线不能挪。
 *
 * <p><b>它吃两个函数，这两个函数跑在两条互斥的路上，别搞混（§5.3 第 2 步那句的落地）</b>：
 * <ul>
 *   <li>{@link CommandAction} <b>业务动作</b>——只在<b>未命中</b>时跑，里面放动态业务检查与业务写入，
 *       失败抛业务码（409 那一类）。</li>
 *   <li>{@link ReplayVisibility} <b>可见性复核</b>——只在<b>命中重放</b>时跑，只回答
 *       「这个结果对象现在对这一位 actor 还够不够得着」，失败一律 404 {@code RESOURCE_NOT_FOUND}
 *       且<b>不返回旧 data</b>（不可见统一隐藏，不揭示存在性）。</li>
 * </ul>
 * 分界线一句话：<b>「这件事现在能不能做」归业务动作，重放时跳过；「这个结果现在能不能给这个人看」
 * 归复核，重放时必须查。</b>把前者写进复核，会把一个本该重放成功的请求拦成 409；
 * 把后者漏掉，就会把一份已经不该给他看的旧 data 原样递回去。
 *
 * <p><b>本片的现实边界（如实写明，不假装覆盖）</b>：{@code resourceId} 可能是 null
 * （该命令没有单一结果对象）。执行器<b>照样调用复核</b>并把 null 交出去，让调用方显式表态；
 * 机制不替它猜、也不在机制里悄悄放行。
 *
 * <p><b>本类的失败怎么走</b>：业务动作抛 {@code ApiException} → 异常穿出事务 → 连同已登记的幂等记录
 * 一起回滚 → 由全局异常处理器翻成对应错误码。<b>这里没有任何 catch 住业务异常再补写记录的分支</b>，
 * 因为契约明写「只缓存 2xx；失败与幂等记录一起回滚」，不许「catch 住异常后另开事务补写记录」。
 */
@Component
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    /**
     * 允许的"登记轮次"上限。
     *
     * <p>什么时候会用掉第二轮：我们撞了唯一键 → 去读赢家的记录 → <b>读不到</b>。
     * 读不到意味着对手在登记之后又回滚了（它自己业务失败），那行已经不存在，
     * 这时候正确答案是<b>从头再来一遍</b>，而不是报错。
     *
     * <p>为什么给 3 而不是无限循环：反复出现"撞了又没了"说明系统有问题，再转下去只是耗着客户端。
     * 转满仍不成功就返回 503，让客户端用同一个键重试——那也是可重试错误的正确定位。
     */
    private static final int MAX_REGISTRATION_ROUNDS = 3;

    private final IdempotencyMapper idempotencyMapper;
    private final RequestDigest requestDigest;
    private final ObjectMapper objectMapper;

    /** 业务事务：登记占位 → 业务动作 → 回填成功响应，同一笔，一起提交或一起回滚。 */
    private final TransactionTemplate writeTx;

    /**
     * 撞唯一键之后的"再读一次"。
     *
     * <p>显式用 {@code REQUIRES_NEW}，不是为了好看——契约要求「先结束/回滚当前事务，再<b>新建事务</b>
     * 读已提交的成功记录」。虽然异常穿出 {@link #writeTx} 时那笔事务已经回滚并解绑，
     * 直接查也能读到新视图，但把「必须是新事务」写在配置里，将来谁在外面套了一层事务，这条也不会失守。
     */
    private final TransactionTemplate freshReadTx;

    public CommandExecutor(IdempotencyMapper idempotencyMapper,
                           RequestDigest requestDigest,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.idempotencyMapper = idempotencyMapper;
        this.requestDigest = requestDigest;
        this.objectMapper = objectMapper;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.freshReadTx = new TransactionTemplate(transactionManager);
        this.freshReadTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 跑一条命令。
     *
     * @param spec      命令身份（operationId + 具名路径资源 + 允许的 query + 保留策略）
     * @param rawKey    请求头 {@code Idempotency-Key} 的原始值；缺失或非法在这里变 400
     * @param body      <b>已经过字段校验</b>的请求体对象；没有 body 的命令传 null
     * @param requestId 本次请求的编号（取自 {@code RequestIdFilter}）。它会被写进存档的信封里，
     *                  将来重放时作为 {@code Original-Request-Id} 回给客户端
     * @param action    业务动作，<b>只在未命中时</b>执行，且与登记、回填同事务
     * @param visibility 可见性复核，<b>只在命中重放时</b>执行；见 {@link ReplayVisibility}。
     *                  它是必填的：漏掉复核不该是"少写一行、编译照样过"的事，让它在编译期就被逼出来
     * @return 新执行的结果或重放的历史结果
     * @throws ApiException 键非法（400）、同键异内容（409）、结果对象已不可见（404）、
     *                      锁等待超时（503）、未认证（401）
     */
    public <T> CommandResult execute(CommandSpec spec, String rawKey, Object body, String requestId,
                                     CommandAction<T> action, ReplayVisibility visibility) {

        // ① 身份只从安全上下文来。不接受任何客户端自报的 actorId——
        //    否则换个 actorId 就等于换了个没见过的坐标，冲突检测直接被绕开。
        long actorId = currentActorId();

        // ② 键校验。这里只服务 REQUIRED_SUCCESS_REPLAY 那一类，所以"缺失"一律是错误。
        IdempotencyKey key = IdempotencyKey.parse(rawKey);

        // ③ 摘要。契约：认证 token、requestId、键自身都不进摘要。
        String digest = requestDigest.compute(spec, body);

        // ④ 快路径：查成功记录。命中就必须立刻走重放，早于任何动态业务检查。
        //    这一步不占事务——普通 SELECT，读到的就是已提交的数据。
        IdempotencyRecord existing = idempotencyMapper.findByScope(actorId, spec.operationId(), key.value());
        if (existing != null) {
            return replay(existing, digest, spec, visibility);
        }

        // ⑤ 未命中：登记 + 执行 + 回填。注意这条路上<b>完全不会</b>调用 visibility。
        return runRegistrationRounds(actorId, spec, key, digest, requestId, action, visibility);
    }

    // ------------------------------------------------------------------ 未命中的主流程

    private <T> CommandResult runRegistrationRounds(long actorId, CommandSpec spec, IdempotencyKey key,
                                                    String digest, String requestId, CommandAction<T> action,
                                                    ReplayVisibility visibility) {
        for (int round = 1; round <= MAX_REGISTRATION_ROUNDS; round++) {
            try {
                CommandResult result = writeTx.execute(
                        status -> attemptWrite(actorId, spec, key, digest, requestId, action));
                log.info("命令执行成功 operation={} actorId={} requestId={} 轮次={}",
                        spec.operationId(), actorId, requestId, round);
                return result;

            } catch (IdempotencyCollision collision) {
                // 「下车」已经发生了：异常穿出 writeTx.execute 时，那笔事务已回滚并解绑
                // （连带着里面那个半成品占位行一起没了）。所以下面这一句读的是全新的事务视图。
                log.info("幂等键已被并发请求占用，转入重读 operation={} actorId={} requestId={} 轮次={}",
                        spec.operationId(), actorId, requestId, round);
                IdempotencyRecord winner = freshReadTx.execute(
                        status -> idempotencyMapper.findByScope(actorId, spec.operationId(), key.value()));
                if (winner != null) {
                    // 抢赢的那位已经提交了成功结果 → 按重放返回原结果。
                    // 这条路也是重放，所以复核一样要过——不能因为"是从并发冲突绕过来的"就免检。
                    return replay(winner, digest, spec, visibility);
                }
                // 读到空：对手其实回滚了（它自己业务失败），那行不存在 → 下一轮从头再来
                log.info("占用者已回滚，重试登记 operation={} actorId={} 轮次={}",
                        spec.operationId(), actorId, round);

            } catch (PessimisticLockingFailureException e) {
                // 等锁被截断。上限 5 秒由 MySQL 的 innodb_lock_wait_timeout 控制（见回报中的说明）。
                // 这**不是**"程序坏了"，是"这一趟问不出结果"，所以给可重试的 503 而不是 500。
                log.warn("登记锁等待超时 operation={} actorId={} requestId={}", spec.operationId(), actorId, requestId);
                throw ApiException.of(ErrorCode.RETRYABLE_STORAGE_ERROR);
            }
        }
        // 反复"撞了又没了"：提交结果暂不可确定，让客户端用同一个键重试
        log.warn("登记轮次用尽 operation={} actorId={} requestId={}", spec.operationId(), actorId, requestId);
        throw ApiException.of(ErrorCode.RETRYABLE_STORAGE_ERROR);
    }

    /**
     * 一次完整的登记尝试。必须在事务内调用。
     *
     * <p>次序照契约原文：「登记唯一幂等键→按统一锁顺序取业务锁→锁内复核→业务写入、
     * 真实流水和成功 response 同事务提交」。
     */
    private <T> CommandResult attemptWrite(long actorId, CommandSpec spec, IdempotencyKey key, String digest,
                                           String requestId, CommandAction<T> action) {

        IdempotencyRecord record = new IdempotencyRecord(
                actorId, spec.operationId(), key.value(), digest);
        record.setResponseCode(IdempotencyRecord.PLACEHOLDER_RESPONSE_CODE);
        record.setResponseLocation(null);
        record.setResponseJson(IdempotencyRecord.PLACEHOLDER_RESPONSE_JSON);
        record.setResourceId(null);
        record.setKeepUntil(keepUntilFor(spec));
        record.setCreatedAt(UtcTimes.nowUtc());

        // ★ 并发串行化点就在这一句。两个线程拿同一个键同时到，只有一个插得进去，另一个被数据库挡住。
        //   挡住它的是 uk_idem_scope 这条唯一约束，不是这里 SQL 写得好——换成 Redis 锁或进程内锁都不行：
        //   前者会过期、主从切换还会丢；后者出了这一个进程就管不着。
        try {
            idempotencyMapper.insertPlaceholder(record);
        } catch (DuplicateKeyException e) {
            // ★ try 的范围刻意只有这一句。
            //   业务动作自己也可能撞唯一约束（比如注册撞 uk_user_login），抛出的也是 DuplicateKeyException。
            //   要是把 try 放大到整个方法，业务冲突就会被误读成幂等冲突——用户登录名重复，
            //   报回来的却是「幂等键已用」，牛头不对马嘴。
            //   收窄到这一句之后，它只可能撞 uk_idem_scope（另一条唯一索引是主键，业务不会撞自增主键），
            //   于是不必去猜异常消息里的约束名——那种字符串解析迟早会随驱动版本失效。
            throw new IdempotencyCollision(e);
        }

        // ★ 业务动作：与上面的登记、下面的回填同处一笔事务。
        //   它抛 ApiException 时异常直接穿出去 → 整笔回滚 → 连占位行一起没了 →
        //   同一个键可以立刻重试。这就是契约的「失败不缓存」。
        CommandOutcome<T> outcome = action.run();

        Object data = outcome.data();
        JsonNode dataNode = data == null ? null : objectMapper.valueToTree(data);
        record.setResponseCode(outcome.httpStatus());
        record.setResponseLocation(outcome.location());
        record.setResponseJson(envelope(requestId, dataNode));
        record.setResourceId(outcome.resourceId());
        idempotencyMapper.complete(record);

        return new CommandResult(outcome.httpStatus(), outcome.location(), dataNode, false, null);
    }

    // ------------------------------------------------------------------ 重放

    /**
     * 命中成功记录：复核可见性 → 比摘要 → 取出原结果。
     *
     * <p><b>三步的次序照契约 §5.3 第 2 步的字面顺序来</b>，不是我们排的。原文是：
     * 「计算规范摘要，查成功幂等记录。<b>找到后复核关联结果对象当前可见</b>；摘要相同则立即返回原
     * HTTP 状态、Location及data；不同则 409 IDEMPOTENCY_KEY_REUSED。」——复核那句排在
     * 「摘要相同／不同」<b>之前</b>，而摘要与新请求体的比对（第 ②）确实在复核（第 ①）之后。
     * 契约 §10 的人工核对表也是同一个次序：「基础合法且对象可见后读记录」。
     *
     * <p><b>为什么复核必须排在摘要比对之前，而不是反过来。</b>摘要不同意味着"这把键被用在了另一次意图上"，
     * 那是客户端自己的键、自己作用域里的事；可结果对象已经不可见时，连"这把键曾经有过一次成功"
     * 都不该被确认——404 的规矩是不可见统一隐藏。先藏，再说别的。
     *
     * <p>第 ① 步不通过时<b>直接抛 404，且绝不返回旧 data</b>。这不是"少返回点东西"，
     * 而是关键：旧 data 里装的正是那个现在不该给他看的对象的字段。
     */
    private CommandResult replay(IdempotencyRecord record, String digest, CommandSpec spec,
                                 ReplayVisibility visibility) {
        // ① 复核「这份老结果指向的东西，现在还归不归他」。
        //    传下去的是幂等记录里的 resource_id——列早就有、一直在写，到这一步才第一次被读。
        //    注意这里问的<b>不是</b>"现在还能不能做这件事"（那是动态检查，重放时一律跳过）。
        boolean visible = visibility.isVisibleTo(record.getActorId(), record.getResourceId());
        if (!visible) {
            log.info("重放前复核不通过：结果对象已不可见 operation={} actorId={} resourceId={}",
                    spec.operationId(), record.getActorId(), record.getResourceId());
            // 404，不是 409、也不是 403：契约要求不可见统一隐藏，不揭示存在性，
            // 所以不解释是"没找到"还是"不是你的"，也不回任何旧 data。
            throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // ② 再比摘要。摘要不同就是客户端的键用重了，这时绝不能把旧结果还给它——
        //    那是拿一个旧答案回答一个新问题。
        if (!digest.equals(record.getRequestHash())) {
            log.info("同键异摘要 operation={} actorId={}", spec.operationId(), record.getActorId());
            throw ApiException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED)
                    .field(FieldErrors.conflict(FieldErrors.headerPath(IdempotencyKey.HEADER_NAME),
                            "幂等键已存在但请求摘要不同"));
        }

        Integer code = record.getResponseCode();
        if (code == null || code == IdempotencyRecord.PLACEHOLDER_RESPONSE_CODE) {
            // 正常不可能走到这里：占位行只在事务内存在，回填之后才提交。
            // 真读到了说明有半成品提交了出去——宁可炸响也不要拿一个 response_code=0 去糊弄客户端。
            throw new IllegalStateException(
                    "读到未完成的幂等记录（response_code=" + code + "）：占位行不该被提交出去，这是 bug");
        }

        JsonNode stored = objectMapper.readTree(record.getResponseJson());
        JsonNode data = stored.get("data");
        JsonNode originalRequestIdNode = stored.path("requestId");
        String originalRequestId = originalRequestIdNode.isString() ? originalRequestIdNode.stringValue() : null;

        log.info("幂等重放 operation={} actorId={} 原 requestId={}",
                spec.operationId(), record.getActorId(), originalRequestId);
        // 原 data 原样还回去（可能是历史状态），原状态码与原 Location 也是。
        // 本信封里的 requestId 由 CommandResponses 换成本次的，原编号走 Original-Request-Id 头。
        return new CommandResult(code, record.getResponseLocation(), data, true, originalRequestId);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 存档信封：{@code {requestId, data}}，和成功响应体的形状一模一样。
     *
     * <p>存整个信封而不是只存 data，是为了留下原 requestId——重放的 {@code Original-Request-Id} 头
     * 要的就是它。契约：「原 response 含原 requestId 用于审计」。
     */
    private String envelope(String requestId, JsonNode data) {
        return objectMapper.writeValueAsString(new ApiResponse<>(requestId, data));
    }

    /** 保留期取值。语义见 {@link CommandSpec.Retention}，本片只写值，不做清理任务。 */
    private static LocalDateTime keepUntilFor(CommandSpec spec) {
        return switch (spec.retention()) {
            case RESOURCE_WRITE -> UtcTimes.nowUtc().plusDays(CommandSpec.RESOURCE_WRITE_RETENTION_DAYS);
            // NULL = 订单未终态，不得清理。将来订单归档时由清理任务补上具体时间。
            case ORDER_LINKED -> null;
        };
    }

    /**
     * 取当前身份。**只认已校验令牌的 {@code sub}**，不看任何请求字段或自定请求头。
     *
     * <p>理论上传进来的请求都已经过了安全过滤链，走不到这里才发现没认证。
     * 但执行器不假设调用方一定在链后面（将来测试、内部调用都可能直接进来），所以自己兜一次 401。
     */
    private static long currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            // sub 是我们自己签的十进制 userId；解析不了说明令牌来自别处
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
    }

    /**
     * 「幂等键被别人抢先了」的内部信号。
     *
     * <p>为什么要有这个自己的异常，不直接用 {@link DuplicateKeyException}：
     * 那样就没法区分「撞的是幂等记录的唯一约束」和「业务自己撞了唯一约束」。
     * 详见 {@link #attemptWrite} 里的注释。
     *
     * <p>它是运行时异常，抛出会让当前事务回滚——这正是我们要的"下车"。
     */
    private static final class IdempotencyCollision extends RuntimeException {
        IdempotencyCollision(Throwable cause) {
            super("幂等键已被并发请求占用", cause);
        }
    }
}
