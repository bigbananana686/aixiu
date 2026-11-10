package com.autocare.server.command;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code t_idempotency} 的读写。三条语句，正好对应机制的三个动作：
 * <b>插占位</b> → <b>回填成功响应</b> → <b>按作用域查</b>。
 *
 * <p><b>这里没有 DELETE。</b>清理任务归 G2/G4，本片不做；而且契约要求清理必须检查订单终态与归档时间，
 * 不是一句 {@code DELETE WHERE keep_until < now()} 能了事的。
 *
 * <p><b>没有 @Transactional。</b>事务边界不在这层，在执行器。理由：这里的三个动作不是同一种粒度——
 * 插入和回填必须在<b>同一个</b>业务事务里，而「按作用域查」有两次调用：一次在开事务前（快路径，
 * 大概率命中）、一次在撞唯一键之后（必须是<b>另一笔全新事务</b>）。同一个方法要在两种事务语境下用，
 * 就不能把事务注解焊在它身上。
 */
@Mapper
public interface IdempotencyMapper {

    /**
     * 插入占位行——<b>这一步就是并发串行化点本身</b>。
     *
     * <p>两个线程拿着同一个键同时进来，都执行这一条。表上的
     * {@code UNIQUE KEY uk_idem_scope (actor_id, operation, idempotency_key)} 保证只有一个能插进去，
     * 另一个被数据库挡住、等着。注意<b>拦住并发的是这条唯一约束，不是这一行 SQL 写得巧</b>——
     * 换任何写法都拦不住。
     *
     * <p>插进去的是「占位态」（{@code response_code = 0}、{@code response_json = '{}'}），
     * 因为业务动作还没跑、还没有响应可存，而 {@code response_json} 列是 NOT NULL。
     * 真正的响应稍后由 {@link #complete} 回填，两步在同一个事务里，一起提交或一起回滚。
     *
     * @return 影响行数（正常是 1；0 表示没插进去，属于异常）
     * @throws org.springframework.dao.DuplicateKeyException 撞上唯一约束——**这是预期路径之一**，
     *         不是故障。执行器靠它认出「有人先到一步」，然后退出当前事务、另开新事务去读。
     */
    @Insert("INSERT INTO t_idempotency "
            + "(actor_id, operation, idempotency_key, request_hash, response_code, response_location, "
            + " response_json, resource_id, keep_until, created_at) "
            + "VALUES (#{actorId}, #{operation}, #{idempotencyKey}, #{requestHash}, #{responseCode}, "
            + " #{responseLocation,jdbcType=VARCHAR}, #{responseJson}, #{resourceId,jdbcType=BIGINT}, "
            + " #{keepUntil,jdbcType=TIMESTAMP}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPlaceholder(IdempotencyRecord record);

    /**
     * 把占位行补成完成态。
     *
     * <p>{@code idempotency_key} 和 {@code request_hash} <b>不更新</b>：它们是这个作用域的身份，
     * 从插入那一刻就定了。能更新的只有「结果」那一半。
     *
     * <p>按主键更新，不按作用域更新——主键是 {@link #insertPlaceholder} 回填进来的，
     * 拿它定位是唯一不歧义的方式。
     */
    @Update("UPDATE t_idempotency SET "
            + " response_code = #{responseCode}, "
            + " response_location = #{responseLocation,jdbcType=VARCHAR}, "
            + " response_json = #{responseJson}, "
            + " resource_id = #{resourceId,jdbcType=BIGINT}, "
            + " keep_until = #{keepUntil,jdbcType=TIMESTAMP} "
            + "WHERE id = #{id}")
    int complete(IdempotencyRecord record);

    /**
     * 按作用域查成功记录。
     *
     * <p><b>三个参数就是作用域的三个坐标，一个都不能少、也不能换成别的</b>：
     * 不是 URL，不是 body 里自称的 userId。少一个，两个不同的人或者两件不同的事就会被认成同一次。
     *
     * <p>只可能查到一行（唯一约束保证）。查不到返回 null——**那是"这是新请求"，
     * 不是错误**，别写成抛异常。
     *
     * <p>注意它会被两种调用方用到：开事务前的快路径，和撞唯一键之后<b>新事务</b>里的那次读。
     * 两次都是普通的 SELECT，不需要特殊写法——真正决定看到什么的是「在哪笔事务里读」。
     */
    @Select("SELECT id, actor_id, operation, idempotency_key, request_hash, response_code, "
            + "response_location, response_json, resource_id, keep_until, created_at "
            + "FROM t_idempotency "
            + "WHERE actor_id = #{actorId} AND operation = #{operation} AND idempotency_key = #{key}")
    IdempotencyRecord findByScope(@Param("actorId") long actorId,
                                  @Param("operation") String operation,
                                  @Param("key") String key);
}
