package com.autocare.server.catalog.admin;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminCatalogMapper {
  @Select("SELECT * FROM t_service_item WHERE id=#{id}")
  AdminCatalogRow find(long id);

  @Select("SELECT * FROM t_service_item WHERE id=#{id} FOR UPDATE")
  AdminCatalogRow lock(long id);

  @Insert(
    "INSERT INTO t_service_item(code,name,duration_minutes,price,currency,enabled,version,created_at,updated_at) VALUES(#{code},#{name},#{durationMinutes},#{price},'CNY',#{enabled},1,#{createdAt},#{updatedAt})"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(AdminCatalogRow row);

  @Update(
    "UPDATE t_service_item SET name=#{name},duration_minutes=#{durationMinutes},price=#{price},enabled=#{enabled},version=version+1,updated_at=#{updatedAt} WHERE id=#{id} AND version=#{version}"
  )
  int update(AdminCatalogRow row);

  String FILTER =
    "<where><if test='enabled != null'>enabled=#{enabled}</if></where>";

  @Select(
    "<script>SELECT * FROM t_service_item " +
      FILTER +
      " ORDER BY id ASC LIMIT #{limit} OFFSET #{offset}</script>"
  )
  List<AdminCatalogRow> list(
    @Param("enabled") Boolean enabled,
    @Param("limit") int limit,
    @Param("offset") int offset
  );

  @Select("<script>SELECT COUNT(*) FROM t_service_item " + FILTER + "</script>")
  long count(Boolean enabled);
}
