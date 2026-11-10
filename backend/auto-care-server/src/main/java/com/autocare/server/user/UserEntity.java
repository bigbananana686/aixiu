package com.autocare.server.user;

import com.autocare.common.identity.Role;

import java.time.LocalDateTime;

/**
 * t_user 一行的 Java 投影（字典 §3.1）。
 *
 * <p>命名规矩：**Java 字段一律 camelCase，数据库列名一律 snake_case**，两边靠 MyBatis 的
 * map-underscore-to-camel-case 对上。别把列名搬进 Java——查的时候那个开关找不到同名属性、
 * 不会报错，只会把那几格留成 null（看着像查成功）；插的时候 #{loginName} 找不到属性则直接抛异常。
 */
public class UserEntity {

    private Long id;
    private String loginName;
    private String passwordHash;
    private Role role;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserEntity() {
    }

    public UserEntity(Long id, String loginName, String passwordHash, Role role,
                      boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.loginName = loginName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLoginName() {
        return loginName;
    }

    public void setLoginName(String loginName) {
        this.loginName = loginName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
