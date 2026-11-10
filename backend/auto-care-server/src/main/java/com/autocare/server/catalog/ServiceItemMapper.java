// ServiceItemMapper.java
package com.autocare.server.catalog;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ServiceItemMapper {
  @Select(
    "SELECT id, code, name, duration_minutes, price, currency, version " +
      "FROM t_service_item WHERE enabled = 1 " +
      "ORDER BY id ASC LIMIT #{limit} OFFSET #{offset}"
  )
  List<ServiceItemRow> listEnabled(
    @Param("limit") int limit,
    @Param("offset") int offset
  );

  @Select("SELECT COUNT(*) FROM t_service_item WHERE enabled = 1")
  long countEnabled();

  @Select(
    "SELECT id, code, name, duration_minutes, price, currency, version FROM t_service_item WHERE enabled=1 AND id=#{id}"
  )
  ServiceItemRow findEnabled(long id);
}
