package com.autocare.server.auth;

import com.autocare.server.user.UserEntity;
import com.autocare.server.web.UtcTimes;

/**
 * {@code GET /api/v1/me} 的响应体，对应契约 {@code components.schemas.MeResult}。
 *
 * <p>与注册结果的区别只有一处：{@code role} 是**该账号的真实角色**（OWNER / TECH / ADMIN 都可能），
 * 而不是固定的 OWNER。
 *
 * <p>身份只来自「已验证令牌 + 账号状态核验」——这里的 {@code enabled} 也是从库里读的当前值，
 * 不是令牌里那个副本。
 */
public record MeResult(
        String userId,
        String loginName,
        String role,
        boolean enabled,
        String createdAt) {

    public static MeResult from(UserEntity entity) {
        return new MeResult(
                String.valueOf(entity.getId()),
                entity.getLoginName(),
                entity.getRole().name(),
                entity.isEnabled(),
                UtcTimes.format(entity.getCreatedAt()));
    }
}
