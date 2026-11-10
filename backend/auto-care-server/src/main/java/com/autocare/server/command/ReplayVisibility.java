package com.autocare.server.command;

/**
 * 重放前的<b>可见性复核</b>——「这份老结果指向的那个东西，现在对这位 actor 还够得着吗」。
 *
 * <p><b>它和 {@link CommandAction} 不是一回事，这一条别搞混。</b>两个接口都是「调用方填进来的一个函数」，
 * 但跑在两条互斥的路线上、回答两个不同的问题、失败码也不一样：
 *
 * <table border="1">
 *   <caption>两处函数的分工</caption>
 *   <tr><th></th><th>{@link CommandAction}（业务动作）</th><th>本接口（可见性复核）</th></tr>
 *   <tr><td>什么时候跑</td><td><b>只在未命中时</b>：新执行那条路</td><td><b>只在命中时</b>：重放那条路</td></tr>
 *   <tr><td>问什么</td><td>「这件事现在<b>能不能做</b>」——订单还 READY 吗、报价过期没、目录还上架吗，然后写库</td>
 *       <td>「这个结果<b>现在还能不能给这个人看</b>」——只查存在与归属</td></tr>
 *   <tr><td>失败码</td><td>409 一类的业务码（{@code ORDER_STATE_CONFLICT}、{@code QUOTE_EXPIRED}…），
 *       把原因说清楚</td><td>404 {@code RESOURCE_NOT_FOUND}，不可见统一隐藏，连"它存在"都不透露</td></tr>
 * </table>
 *
 * <p><b>为什么必须分成两个口子，不能合成一个。</b>契约 §5.3 第 2 步明写：历史成功重放
 * <b>不执行</b>动态业务检查。所以「订单现在是不是 SERVICING 了」这种问题在重放时<b>必须跳过</b>
 * ——老结果是「当时那一下成了没有」的记录，它自己不会过期。反过来，「结果对象现在还归不归他」
 * 是<b>必须查</b>的：§5.1 一句「成功结果只供当前仍有权限的同 actor 重放」。
 * 把两件事混在一起的后果是两个方向都错：要么该放过的被拦成 409，要么该拦的照旧把旧 data 递出去。
 *
 * <p><b>一条能自查的尺子</b>：这个函数里只要出现 {@code state}、{@code expires_at}、
 * {@code row_version}、目录是否上架这类字段，就已经越界了——那些是动态检查的活。
 * 它只该回答「在不在、是不是你的」，返回值就是一个「能看／不能看」。
 *
 * <p><b>关于 {@code resourceId} 可能是 null。</b>它取自幂等记录的 {@code resource_id} 列，
 * 而有些命令的结果压根不是「某一个资源」（例如批量替换工作窗口），那一列就是 null。
 * 执行器<b>照样会调用复核</b>，把 null 原样交给你——「没有单独的结果对象」这件事该由调用方
 * 明确表个态，不该由机制去猜、更不该在机制里悄悄放行。所以实现里请显式处理 null 分支。
 *
 * <p><b>不通过就别返回 true。</b>返回 false 时执行器抛 404 且<b>不返回旧 data</b>
 * （契约：不可见统一隐藏，不揭示存在性）。所以"查不到也算够不着"一定要落在这一侧。
 */
@FunctionalInterface
public interface ReplayVisibility {

    /**
     * 这个结果对象现在对这位 actor 还可见吗。
     *
     * @param actorId    当前身份，取自安全上下文（不是客户端自报）
     * @param resourceId 幂等记录里的业务主键，<b>可能为 null</b>（该命令没有单一结果对象）
     * @return true = 仍可见，放行重放；false = 已不可见，执行器抛 404 且不返回旧 data
     */
    boolean isVisibleTo(long actorId, Long resourceId);
}
