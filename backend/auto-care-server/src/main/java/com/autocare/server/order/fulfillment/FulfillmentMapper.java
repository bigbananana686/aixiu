package com.autocare.server.order.fulfillment;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FulfillmentMapper {
  @Update(
    "UPDATE t_service_order SET state='SERVICING',actual_start=#{now},row_version=row_version+1,updated_at=#{now} WHERE id=#{id} AND state='READY_FOR_SERVICE' AND row_version=#{version}"
  )
  int start(
    @Param("id") long id,
    @Param("version") int version,
    @Param("now") LocalDateTime now
  );

  @Update(
    "UPDATE t_service_order SET state='COMPLETED',actual_finish=#{finish},service_result=#{result},mileage_snapshot=COALESCE(#{mileage},mileage_snapshot),row_version=row_version+1,updated_at=#{now} WHERE id=#{id} AND state='SERVICING' AND row_version=#{version}"
  )
  int complete(
    @Param("id") long id,
    @Param("version") int version,
    @Param("finish") LocalDateTime finish,
    @Param("result") String result,
    @Param("mileage") Integer mileage,
    @Param("now") LocalDateTime now
  );
}
