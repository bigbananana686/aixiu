package com.autocare.server.order.reschedule;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RescheduleMapper {
  @Update(
    "UPDATE t_service_order SET service_start=#{start},occupied_start=#{occupied},committed_end=#{end},effective_end=#{end},row_version=row_version+1,updated_at=#{now} WHERE id=#{id} AND row_version=#{version}"
  )
  int update(
    @Param("id") long id,
    @Param("version") int version,
    @Param("start") LocalDateTime start,
    @Param("occupied") LocalDateTime occupied,
    @Param("end") LocalDateTime end,
    @Param("now") LocalDateTime now
  );
}
