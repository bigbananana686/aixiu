package com.autocare.server.order.booking;

import com.autocare.server.availability.OccupiedRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 订单写路径的查询与写入。**只有这里允许出现 FOR UPDATE / FOR SHARE**——
 * 锁的次序（幂等记录 → 技师资源行 → 订单行）由 G1-01 的 CommandExecutor 加本类的调用顺序共同保证。
 */
@Mapper
public interface OrderMapper {
  /** 锁技师资源行（设计 §7.1：一切占用/窗口变更先锁本行）。行不存在或停用返回 null → 404。 */
  @Select(
    "SELECT user_id, commute_minutes FROM t_technician " +
      "WHERE user_id = #{technicianId} AND enabled = 1 FOR UPDATE"
  )
  TechnicianLockRow lockTechnician(@Param("technicianId") long technicianId);

  /** 锁内数据库时间：「occupiedStart 不得早于锁内 now」的 now 就是它，不用应用机时钟。 */
  @Select("SELECT UTC_TIMESTAMP(3)")
  LocalDateTime dbNowUtc();

  /** 整段占用必须落在技师某个工作窗口之内（设计 §3.2）。 */
  @Select(
    "SELECT COUNT(*) FROM t_technician_window " +
      "WHERE technician_id = #{technicianId} " +
      "AND window_start <= #{occupiedStart} AND window_end >= #{bookingEnd}"
  )
  int countWindowsCovering(
    @Param("technicianId") long technicianId,
    @Param("occupiedStart") LocalDateTime occupiedStart,
    @Param("bookingEnd") LocalDateTime bookingEnd
  );

  /**
   * 与新占用重叠的有效预约（半开区间：{@code occupied_start < 新end AND effective_end > 新start}，
   * 端点相接不算冲突）。走 {@code idx_order_conflict}。
   */
  @Select(
    "SELECT occupied_start, effective_end FROM t_service_order " +
      "WHERE technician_id = #{technicianId} AND reservation_active = 1 " +
      "AND occupied_start < #{bookingEnd} AND effective_end > #{occupiedStart}"
  )
  List<OccupiedRow> findConflicts(
    @Param("technicianId") long technicianId,
    @Param("occupiedStart") LocalDateTime occupiedStart,
    @Param("bookingEnd") LocalDateTime bookingEnd
  );

  /**
   * 锁内稳定读取目录：按 serviceItemId 升序 + FOR SHARE（契约 §5.1 事务内顺序明文）。
   * 版本核对与停用核对都在读出来之后由 service 做——数据库只负责把这一刻的行定住。
   */
  @Select(
    "<script>SELECT id, code, name, price, currency, duration_minutes, version, enabled " +
      "FROM t_service_item WHERE id IN " +
      "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
      "ORDER BY id FOR SHARE</script>"
  )
  List<CatalogQuoteRow> findCatalogForShare(@Param("ids") List<Long> ids);

  @Insert(
    "INSERT INTO t_service_order (order_no, owner_id, vehicle_id, technician_id, state, row_version, " +
      "service_start, occupied_start, committed_end, effective_end, accept_deadline, " +
      "booking_items_json, rules_snapshot_json, address_snapshot, raw_input, mileage_snapshot, " +
      "model_snapshot, plate_snapshot, ai_parse_id, created_at, updated_at) " +
      "VALUES (#{orderNo}, #{ownerId}, #{vehicleId}, #{technicianId}, #{state}, #{rowVersion}, " +
      "#{serviceStart}, #{occupiedStart}, #{committedEnd}, #{effectiveEnd}, #{acceptDeadline}, " +
      "#{bookingItemsJson}, #{rulesSnapshotJson}, #{addressSnapshot}, #{rawInput}, #{mileageSnapshot}, " +
      "#{modelSnapshot}, #{plateSnapshot}, #{aiParseId}, #{createdAt}, #{updatedAt})"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertOrder(OrderInsertRow row);

  @Select(
    "SELECT id FROM t_ai_call_log WHERE id=#{id} AND actor_id=#{actor} AND scene='BOOKING_DRAFT' AND outcome='SUCCEEDED' FOR SHARE"
  )
  Long ownedBookingParse(@Param("id") long id, @Param("actor") long actor);

  @Update("UPDATE t_service_order SET order_no = #{orderNo} WHERE id = #{id}")
  int updateOrderNo(@Param("id") long id, @Param("orderNo") String orderNo);

  /** 创建流水：fromState 为 NULL（创建时没有前态），action 固定 CREATE_ORDER。 */
  @Insert(
    "INSERT INTO t_order_state_log (order_id, from_state, to_state, action, actor_id, request_id, created_at) " +
      "VALUES (#{orderId}, NULL, 'RESERVED', 'CREATE_ORDER', #{actorId}, #{requestId}, #{createdAt})"
  )
  int insertCreateStateLog(
    @Param("orderId") long orderId,
    @Param("actorId") long actorId,
    @Param("requestId") String requestId,
    @Param("createdAt") LocalDateTime createdAt
  );

  /** 重放前的可见性复核：这张订单还在、还是这位车主的。 */
  @Select(
    "SELECT COUNT(*) FROM t_service_order WHERE id = #{orderId} AND owner_id = #{ownerId}"
  )
  long countForOwner(
    @Param("orderId") long orderId,
    @Param("ownerId") long ownerId
  );

  /** 锁技师那一行的投影（普通类：MyBatis setter 注值）。 */
  class TechnicianLockRow {

    private long userId;
    private int commuteMinutes;

    public long getUserId() {
      return userId;
    }

    public void setUserId(long userId) {
      this.userId = userId;
    }

    public int getCommuteMinutes() {
      return commuteMinutes;
    }

    public void setCommuteMinutes(int commuteMinutes) {
      this.commuteMinutes = commuteMinutes;
    }
  }
}
