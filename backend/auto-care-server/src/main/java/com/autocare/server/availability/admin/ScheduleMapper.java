package com.autocare.server.availability.admin;

import com.autocare.server.availability.WindowRow;
import com.autocare.server.order.OrderRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ScheduleMapper {
  String COLUMNS =
    "user_id technician_id,display_name,enabled,schedule_version";

  @Select("SELECT " + COLUMNS + " FROM t_technician WHERE user_id=#{id}")
  ScheduleRow find(long id);

  @Select(
    "SELECT " + COLUMNS + " FROM t_technician WHERE user_id=#{id} FOR UPDATE"
  )
  ScheduleRow lock(long id);

  String FILTER =
    "<where><if test='enabled != null'>enabled=#{enabled}</if></where>";

  @Select(
    "<script>SELECT " +
      COLUMNS +
      " FROM t_technician " +
      FILTER +
      " ORDER BY user_id ASC LIMIT #{limit} OFFSET #{offset}</script>"
  )
  List<ScheduleRow> list(
    @Param("enabled") Boolean enabled,
    @Param("limit") int limit,
    @Param("offset") int offset
  );

  @Select("<script>SELECT COUNT(*) FROM t_technician " + FILTER + "</script>")
  long count(Boolean enabled);

  @Select(
    "SELECT window_start,window_end FROM t_technician_window WHERE technician_id=#{id} ORDER BY window_start,window_end"
  )
  List<WindowRow> windows(long id);

  @Select(
    "SELECT occupied_start,effective_end FROM t_service_order WHERE technician_id=#{id} AND reservation_active=1"
  )
  List<OrderRow> reservations(long id);

  @Delete(
    "DELETE FROM t_technician_window WHERE technician_id=#{id} AND window_start<#{end} AND window_end>#{start}"
  )
  int removeRange(
    @Param("id") long id,
    @Param("start") LocalDateTime start,
    @Param("end") LocalDateTime end
  );

  @Insert(
    "INSERT INTO t_technician_window(technician_id,window_start,window_end,created_at,updated_at) VALUES(#{id},#{start},#{end},#{now},#{now})"
  )
  int insert(
    @Param("id") long id,
    @Param("start") LocalDateTime start,
    @Param("end") LocalDateTime end,
    @Param("now") LocalDateTime now
  );

  @Update(
    "UPDATE t_technician SET schedule_version=schedule_version+1,updated_at=#{now} WHERE user_id=#{id} AND schedule_version=#{version}"
  )
  int advance(
    @Param("id") long id,
    @Param("version") int version,
    @Param("now") LocalDateTime now
  );
}
