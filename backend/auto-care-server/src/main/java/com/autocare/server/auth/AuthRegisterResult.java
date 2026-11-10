package com.autocare.server.auth;

import com.autocare.common.identity.Role;
import com.autocare.server.user.UserEntity;
import com.autocare.server.web.UtcTimes;

/**
 * 注册成功的响应体，对应契约 {@code components.schemas.AuthRegisterResult}。
 *
 * <p>三个形状约定都落实在这里：
 * <ul>
 *   <li>{@code userId} 是**字符串**（ID 一律十进制字符串，避免 JS 的 Number 精度丢失）；
 *       库里是 bigint，转换点就这一个。</li>
 *   <li>{@code role} 固定 {@link Role#OWNER}——注册接口的 schema 把返回值写成常量 OWNER，
 *       不是"当前角色"。</li>
 *   <li>{@code createdAt} 是 UTC 固定 3 位小数。</li>
 * </ul>
 *
 * <p><b>只回本人账户的这五样</b>：不回密码哈希、不回任何凭证。契约明写「成功响应只回本人账户的
 * userId/loginName/role/enabled/createdAt，不回显任何哈希或凭证」。
 */
public record AuthRegisterResult(
        String userId,
        String loginName,
        String role,
        boolean enabled,
        String createdAt) {

    public static AuthRegisterResult from(UserEntity entity) {
        return new AuthRegisterResult(
                String.valueOf(entity.getId()),
                entity.getLoginName(),
                Role.OWNER.name(),
                entity.isEnabled(),
                UtcTimes.format(entity.getCreatedAt()));
    }
}
