package com.autocare.server.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT id, login_name, password_hash, role, enabled, created_at, updated_at " +
            "FROM t_user WHERE login_name = #{loginName}")
    UserEntity findByLoginName(@Param("loginName") String loginName);

    @Insert("INSERT INTO t_user (login_name, password_hash, role, enabled, created_at, updated_at) " +
            "VALUES (#{loginName}, #{passwordHash}, #{role}, #{enabled}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserEntity user);

    @Select("SELECT id, login_name, password_hash, role, enabled, created_at, updated_at " +
            "FROM t_user WHERE id = #{id}")
    UserEntity findById(@Param("id") long id);

    @Select("SELECT id, role, enabled FROM t_user WHERE id = #{id} FOR UPDATE")
    UserEntity lockIdentity(@Param("id") long id);
}
