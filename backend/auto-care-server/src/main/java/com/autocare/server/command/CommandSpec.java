package com.autocare.server.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一条命令的「身份」。摘要拿它 + body 一起算。
 *
 * <p><b>它装的是名字，不是 URL。</b>这是本类存在的主要理由。幂等作用域和摘要是同一个问题的两面：
 * 作用域是「谁 + 哪件事 + 键」，摘要是「这一次意图的内容」。两者都<b>不含 URL</b>——
 * URL 是「哪件事 + 具名路径资源」拼出来的结果，不是身份的组成部分。
 *
 * <p>举个契约里的原例：{@code cancelOrder} 处理 101 和 102 两张单，{@code operationId} 都是
 * {@code "cancelOrder"}（同一个），而 {@code pathResources} 分别是 {@code {orderId: "101"}} 与
 * {@code {orderId: "102"}}（不同）。于是同一个键配上这两次请求 → 作用域相同、摘要不同 →
 * 409 {@code IDEMPOTENCY_KEY_REUSED}。这正是要的效果。要是拿 URL 当坐标，这两次就会被当成两件互不相干的事。
 *
 * <p><b>为什么路径资源要"具名"。</b>写 {@code {orderId: "101"}} 而不是 {@code "orders/101"}：
 * 名字来自 operation 的定义，不受 URL 拼接形式影响（{@code /orders/0101/}、查询参数、大小写、
 * 末尾斜杠都改不动它）。契约说的「具名路径资源」就是这个意思。
 *
 * <p><b>错误类型的分工</b>：本类抛 {@link IllegalArgumentException}，不是 {@code ApiException}。
 * 因为 operationId 写错、路径资源传 null 是<b>我们自己写错代码</b>，不是客户端发错请求。
 * 前者该在开发期就炸（并且不该被 400 信封伪装成"客户端的问题"），后者才走 {@code ApiException} → 400。
 */
public record CommandSpec(String operationId,
                          Map<String, String> pathResources,
                          Map<String, String> queryParams,
                          Retention retention) {

    /**
     * 契约里说的「API 版本」。进摘要。
     *
     * <p>写死成常量而不是从 URL 里抠出来：抠出来的话，「{@code /api/v1/...}」和
     * 「{@code /api/v2/...}」就成了两个不同的输入——而它们本来该由路由分别映射到两个 operation。
     * 摘要里带上版本，是为了让「同一套 body 在 v1 和 v2 下语义不同」这件事在摘要上分开。
     */
    public static final String API_VERSION = "v1";

    /** 非订单资源写的保留期（契约：至少成功后 30 个完整 UTC 日）。 */
    public static final int RESOURCE_WRITE_RETENTION_DAYS = 30;

    /**
     * 幂等记录能留多久——决定 {@code keep_until} 那一格写什么。
     *
     * <p>契约把保留期分成两类，而且这两类的<b>算法不一样</b>（不是同一个数字换个名字）：
     * <ul>
     *   <li>{@link #RESOURCE_WRITE}：车辆这类非订单资源写。从<b>成功那一刻</b>起算 30 天。</li>
     *   <li>{@link #ORDER_LINKED}：订单命令与订单终态/归档挂钩。<b>写 NULL</b>——NULL 的含义是
     *       「订单还没到终态，不许清」。等订单归档了，才由将来的清理任务补上具体时间。</li>
     * </ul>
     * <p>本片只把语义和取值定清楚，<b>不实现清理任务</b>（归 G2/G4）。
     */
    public enum Retention {
        RESOURCE_WRITE,
        ORDER_LINKED
    }

    public CommandSpec {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(retention, "retention");
        if (operationId.isBlank()) {
            throw new IllegalArgumentException("operationId 不能为空白");
        }
        // 两份都拷成不可变副本：CommandSpec 是要被当成"这一次请求的身份"传来传去的，
        // 谁都不该在算完摘要之后还能改动它里面的东西。
        pathResources = Map.copyOf(pathResources);
        queryParams = Map.copyOf(queryParams);
    }

    /**
     * 一个非订单资源写命令（例如 {@code createVehicle}）：不带路径资源、不带 query、保留期 30 天。
     *
     * <p>默认取 {@code RESOURCE_WRITE} 而不是 {@code ORDER_LINKED}：两者相比，
     * 30 天是一个<b>更紧</b>的保留承诺。取更紧的那个，漏了反而早知道；
     * 取 NULL 则是"永远不清"，漏了就一直不清，谁也不会发现。
     */
    public static CommandSpec of(String operationId) {
        return new CommandSpec(operationId, Map.of(), Map.of(), Retention.RESOURCE_WRITE);
    }

    /** 订单类命令：{@code keep_until} 写 NULL（未终态不得清理）。 */
    public CommandSpec orderLinked() {
        return new CommandSpec(operationId, pathResources, queryParams, Retention.ORDER_LINKED);
    }

    /**
     * 追加一个具名路径资源。
     *
     * @param name  来自 operation 定义的参数名，例如 {@code "orderId"}
     * @param value <b>已校验过的规范形式</b>（ID 是十进制字符串，无前导零）；本类不负责校验它
     */
    public CommandSpec pathResource(String name, String value) {
        return new CommandSpec(operationId, with(pathResources, name, value), queryParams, retention);
    }

    /**
     * 追加一个该 operation <b>声明允许</b>的语义 query 参数。
     *
     * <p>契约写的是「该操作允许的语义 query」。所以这里不该顺手把客户端发来的所有 query 都塞进来：
     * 只放契约承认的那些，其余（追踪参数、缓存击穿用的时间戳）本来就不该影响幂等判定。
     */
    public CommandSpec query(String name, String value) {
        return new CommandSpec(operationId, pathResources, with(queryParams, name, value), retention);
    }

    private static Map<String, String> with(Map<String, String> source, String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isBlank()) {
            throw new IllegalArgumentException("路径资源/query 的参数名不能为空白");
        }
        Map<String, String> copy = new LinkedHashMap<>(source);
        copy.put(name, value);
        return copy;
    }
}
