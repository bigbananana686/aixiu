package com.autocare.server.user.admin;

import com.autocare.common.identity.Role;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdminUserMapper {
  String COLUMNS = "id, login_name, role, enabled, admin_version, created_at, updated_at";
  String FILTER = """
    WHERE (#{keyword} = '' OR LOCATE(#{keyword}, login_name) > 0)
      AND (#{role} = '' OR role = #{role})
      AND (#{enabled,jdbcType=BOOLEAN} IS NULL OR enabled = #{enabled,jdbcType=BOOLEAN})
    """;

  record Row(long id, String loginName, Role role, boolean enabled, int adminVersion,
             LocalDateTime createdAt, LocalDateTime updatedAt) {}

  @Select("SELECT " + COLUMNS + " FROM t_user " + FILTER + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
  List<Row> list(@Param("keyword") String keyword, @Param("role") String role,
                 @Param("enabled") Boolean enabled, @Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT COUNT(*) FROM t_user " + FILTER)
  long count(@Param("keyword") String keyword, @Param("role") String role, @Param("enabled") Boolean enabled);

  @Select("SELECT " + COLUMNS + " FROM t_user WHERE id = #{id}")
  Row find(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM t_user WHERE id = #{id} FOR UPDATE")
  Row lock(@Param("id") long id);

  @Select("SELECT user_id FROM t_technician WHERE user_id = #{id} FOR UPDATE")
  Long lockTechnician(@Param("id") long id);

  @Select("""
    SELECT EXISTS(SELECT 1 FROM t_service_order
      WHERE (owner_id = #{id} OR technician_id = #{id}) AND state NOT IN ('CLOSED', 'CANCELLED'))
    """)
  boolean hasUnclosedOrder(@Param("id") long id);

  @Update("""
    UPDATE t_user SET role = #{role}, enabled = #{enabled}, admin_version = admin_version + 1,
      updated_at = UTC_TIMESTAMP(3) WHERE id = #{id}
    """)
  int update(@Param("id") long id, @Param("role") Role role, @Param("enabled") boolean enabled);

  @Insert("""
    INSERT INTO t_technician (user_id, display_name, enabled, created_at, updated_at)
      VALUES (#{id}, #{name}, #{enabled}, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
    """)
  int insertTechnician(@Param("id") long id, @Param("name") String name, @Param("enabled") boolean enabled);

  @Update("UPDATE t_technician SET enabled = #{enabled}, updated_at = UTC_TIMESTAMP(3) WHERE user_id = #{id}")
  int setTechnicianEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

  @Insert("""
    INSERT INTO t_user_access_change (actor_id, user_id, previous_role, new_role, previous_enabled, new_enabled,
      request_id, created_at) VALUES (#{actor}, #{previous.id}, #{previous.role}, #{role}, #{previous.enabled},
      #{enabled}, #{requestId}, UTC_TIMESTAMP(3))
    """)
  int audit(@Param("actor") long actor, @Param("previous") Row previous,
            @Param("role") Role role, @Param("enabled") boolean enabled, @Param("requestId") String requestId);
}
