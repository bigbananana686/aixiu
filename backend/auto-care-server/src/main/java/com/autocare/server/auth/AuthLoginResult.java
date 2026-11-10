package com.autocare.server.auth;

import com.autocare.common.identity.Role;
import com.autocare.server.user.UserEntity;
import com.autocare.server.web.UtcTimes;

/**
 * 登录成功的响应体，对应契约 {@code components.schemas.AuthLoginResult}。
 *
 * <p>字段全部必填，缺一不可：{@code accessToken / tokenType / expiresInSeconds / expiresAt /
 * userId / loginName / role}。其中 {@code tokenType} 固定 {@code "Bearer"}，
 * {@code expiresInSeconds} 是 1800（30 分钟，来自 {@code autocare.jwt.ttl-seconds}）。
 *
 * <p><b>令牌只出现在响应体里</b>：不进 URL、不进日志、不进响应头。这条由 Controller 保证——
 * 响应的 {@code Cache-Control: no-store} 也在那一层加。
 */
public record AuthLoginResult(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String expiresAt,
        String userId,
        String loginName,
        String role) {

    public static AuthLoginResult of(String accessToken, long expiresInSeconds, String expiresAtUtc,
                                     UserEntity entity) {
        return new AuthLoginResult(
                accessToken,
                "Bearer",
                expiresInSeconds,
                expiresAtUtc,
                String.valueOf(entity.getId()),
                entity.getLoginName(),
                entity.getRole().name());
    }

    /** 令牌类型只此一个取值，写成常量避免各处手打字符串。 */
    public static String bearerTokenType() {
        return "Bearer";
    }

    /** 供上层拼 expiresAt 时复用同一套格式（UTC 固定 3 位小数）。 */
    public static String formatExpiresAt(java.time.Instant instant) {
        return UtcTimes.format(instant);
    }

    /** 角色名，给将来需要的地方复用（当前由 of() 内部使用）。 */
    public static String roleName(Role role) {
        return role.name();
    }
}
