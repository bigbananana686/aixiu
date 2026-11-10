package com.autocare.server.command;

/**
 * 命令的<b>业务动作</b>——「真正要干的那件事」，被当成一个"待执行的东西"交给执行器。
 *
 * <p>这个接口存在的唯一理由，是把「什么时候执行」的控制权交给执行器。看它的用法：
 *
 * <pre>{@code
 * return CommandResponses.toEntity(
 *         commands.execute(spec, key, body, requestId,
 *                 () -> vehicleService.create(actorId, body),          // ← 现在不执行，只是"递过去"
 *                 (actor, resourceId) -> vehicleMapper.isOwnedBy(actor, resourceId)),  // ← 另一个口子，见 ReplayVisibility
 *         request);
 * }</pre>
 *
 * <p><b>执行器吃两个函数，别把它们的活儿弄反了。</b>本接口是<b>业务动作</b>，只在"这个键没见过"
 * 时跑，里面放动态业务检查与业务写入，失败抛业务码。另一半是 {@link ReplayVisibility}——
 * 可见性复核，只在"这个键见过、要重放"时跑，只回答"这个结果对象现在还够不够得着"，
 * 失败一律 404 且不返回旧 data。两者的界线见那个接口的说明。
 *
 * <p>要是改成先调 {@code vehicleService.create(...)} 再把结果传进去，业务动作就跑在执行器<b>之前</b>了——
 * 重放还没来得及发生，库已经被写了一遍。这不是风格问题，是契约第 4 条的次序问题。
 *
 * <p><b>里面该放什么、不该放什么</b>：
 * <ul>
 *   <li><b>该放</b>：动态业务检查（订单还 READY 吗、报价过期没、目录还上架吗）、业务写入、真实流水。</li>
 *   <li><b>不该放</b>：认证与角色（更外层做了）、字段格式校验（执行器之前做了）——
 *       契约的次序要求这些排在重放<b>前面</b>，放进 lambda 就跑到后面去了。</li>
 * </ul>
 *
 * <p>失败就抛 {@code ApiException}。那笔事务会连同已经登记的幂等记录一起回滚——
 * 这正是契约要的「失败不缓存、可用同键重试」。别在这里面 catch 住业务异常自己咽掉。
 */
@FunctionalInterface
public interface CommandAction<T> {

    /** 执行。同一笔事务内、登记幂等键之后调用。 */
    CommandOutcome<T> run();
}
