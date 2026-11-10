package com.autocare.server.order.lifecycle;

import com.autocare.server.order.OrderRow;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderLifecycleMapper {
    String COLUMNS = "id, order_no, owner_id, vehicle_id, technician_id, state, row_version, "
            + "service_start, occupied_start, committed_end, effective_end, reservation_active, "
            + "approved_quote_id, pending_quote_id, accept_deadline, booking_items_json, rules_snapshot_json, "
            + "address_snapshot, mileage_snapshot, model_snapshot, plate_snapshot, actual_start, actual_finish, "
            + "service_result, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM t_service_order WHERE id = #{id}")
    OrderRow find(long id);

    /** 现有订单即使技师停用仍需释放资源；不把 enabled 条件混入资源互斥协议。 */
    @Select("SELECT user_id FROM t_technician WHERE user_id = #{id} FOR UPDATE")
    Long lockTechnician(long id);

    @Select("SELECT " + COLUMNS + " FROM t_service_order WHERE id = #{id} FOR UPDATE")
    OrderRow lockOrder(long id);

    @Update("UPDATE t_service_order SET state = #{state}, row_version = row_version + 1, "
            + "reservation_active = #{active}, updated_at = #{now} WHERE id = #{id} "
            + "AND state = #{oldState} AND row_version = #{version}")
    int transition(@Param("id") long id, @Param("oldState") String oldState, @Param("version") int version,
                   @Param("state") String state, @Param("active") boolean active, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO t_order_state_log (order_id, from_state, to_state, action, actor_id, request_id, created_at) "
            + "VALUES (#{id}, #{from}, #{to}, #{action}, #{actor}, #{requestId}, #{now})")
    int insertStateLog(@Param("id") long id, @Param("from") String from, @Param("to") String to,
                       @Param("action") String action, @Param("actor") long actor,
                       @Param("requestId") String requestId, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO t_order_operation (order_id, action, actor_id, payload_summary, request_id, created_at) "
            + "VALUES (#{id}, #{action}, #{actor}, #{summary}, #{requestId}, #{now})")
    int insertOperation(@Param("id") long id, @Param("action") String action, @Param("actor") long actor,
                        @Param("summary") String summary, @Param("requestId") String requestId,
                        @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM t_order_state_log WHERE order_id = #{id} AND action = 'EXPIRE_ACCEPT'")
    int countAcceptanceExpiry(long id);

    @Select("SELECT from_state, to_state, action, CAST(actor_id AS CHAR) actor_id, request_id, "
            + "DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f') created_at FROM t_order_state_log "
            + "WHERE order_id = #{id} ORDER BY id")
    List<OrderStateLogEntry> stateLog(long id);

    @Select("SELECT CAST(id AS CHAR) operation_id, action, CAST(actor_id AS CHAR) actor_id, "
            + "CAST(quote_id AS CHAR) quote_id, payload_summary, request_id, "
            + "DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f') created_at FROM t_order_operation "
            + "WHERE order_id = #{id} ORDER BY id")
    List<OrderOperationEntry> operations(long id);

    String FILTER = " WHERE <choose><when test='owner'>owner_id</when><otherwise>technician_id</otherwise></choose> = #{actor} "
            + "<if test='states != null'> AND state IN <foreach collection='states' item='s' open='(' separator=',' close=')'>#{s}</foreach></if> "
            + "<if test='from != null'> AND created_at &gt;= #{from}</if> "
            + "<if test='to != null'> AND created_at &lt; #{to}</if> ";

    @Select("<script>SELECT " + COLUMNS + " FROM t_service_order " + FILTER
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<OrderRow> list(@Param("actor") long actor, @Param("owner") boolean owner,
                        @Param("states") List<String> states, @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to, @Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM t_service_order " + FILTER + "</script>")
    long count(@Param("actor") long actor, @Param("owner") boolean owner,
               @Param("states") List<String> states, @Param("from") LocalDateTime from,
               @Param("to") LocalDateTime to);

    @Select("SELECT " + COLUMNS + " FROM t_service_order ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<OrderRow> listForAdmin(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM t_service_order")
    long countForAdmin();

    /** 候选查询无锁；每个候选必须在新短事务内按资源→订单顺序重验。 */
    @Select("SELECT id FROM t_service_order WHERE state = 'RESERVED' AND accept_deadline <= UTC_TIMESTAMP(3) "
            + "ORDER BY accept_deadline, id LIMIT 100")
    List<Long> expiredCandidates();

    @Select("SELECT id FROM t_user WHERE login_name = 'system_accept_expiry' AND enabled = 0")
    long expiryActor();
}
