package com.autocare.server.availability;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可用时段计算的查询层。**只有四条 SELECT**——本接口是提示，不是写路径，
 * 所以这里没有幂等记录、没有资源行锁；真正的占用判定在 G1-03 的 createOrder 锁内重做。
 *
 * <p>时间参数全部按 UTC 传 {@code LocalDateTime}（库里 DATETIME(3) 存的就是 UTC，
 * 见 {@link WindowRow} 的说明）。
 */
@Mapper
public interface AvailabilityMapper {

    /** 启用中的技师资源行。停用技师不出现在可用时段里。 */
    @Select("SELECT t.user_id AS technician_id, t.display_name, t.commute_minutes "
            + "FROM t_technician t WHERE t.enabled = 1")
    List<TechnicianRow> listEnabledTechnicians();

    /**
     * 某技师与「查询日」相交的窗口。窗口与日界相交就取出来，**裁剪到日界之内**这件事
     * 归 service 做——SQL 只负责把可能相关的行捞出来，不在数据库里做时区/日界运算。
     */
    @Select("SELECT window_start, window_end FROM t_technician_window "
            + "WHERE technician_id = #{technicianId} "
            + "AND window_start < #{rangeEnd} AND window_end > #{rangeStart} "
            + "ORDER BY window_start")
    List<WindowRow> findWindows(@Param("technicianId") long technicianId,
                                @Param("rangeStart") LocalDateTime rangeStart,
                                @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * 某技师仍在占用的预约中、与查询日相交的行。走
     * {@code idx_order_conflict (technician_id, reservation_active, occupied_start)} 前两列。
     */
    @Select("SELECT occupied_start, effective_end FROM t_service_order "
            + "WHERE technician_id = #{technicianId} AND reservation_active = 1 "
            + "AND occupied_start < #{rangeEnd} AND effective_end > #{rangeStart}")
    List<OccupiedRow> findActiveReservations(@Param("technicianId") long technicianId,
                                             @Param("rangeStart") LocalDateTime rangeStart,
                                             @Param("rangeEnd") LocalDateTime rangeEnd);

    /** 按主键批量取目录项——存在性、启用位、工时三项在本接口里都要。 */
    @Select("<script>SELECT id, duration_minutes, enabled FROM t_service_item WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<CatalogItemRow> findItems(@Param("ids") List<Long> ids);
}
