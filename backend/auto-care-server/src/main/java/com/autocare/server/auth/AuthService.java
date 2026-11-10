package com.autocare.server.auth;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.common.identity.Role;
import com.autocare.server.security.JwtTokenIssuer;
import com.autocare.server.user.UserEntity;
import com.autocare.server.user.UserMapper;
import com.autocare.server.web.UtcTimes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 身份业务。三条规则集中在这里：注册只产 OWNER、凭证错误不区分原因、账号状态每次核库。
 *
 * <p>这一层是"编排 + 领域规则"：它知道顺序、知道该抛哪个业务码，但不碰 SQL 细节（Mapper 的）、
 * 也不碰 HTTP 细节（Controller 的）。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenIssuer tokenIssuer;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenIssuer tokenIssuer) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
    }

    // ------------------------------------------------------------------ 注册

    @Transactional
    public AuthRegisterResult register(AuthRegisterRequest request) {
        String loginName = validateCredentials(request.loginName(), request.password());

        UserEntity entity = new UserEntity();
        entity.setLoginName(loginName);
        // 密码不 trim、不做 Unicode 折叠：契约明写这两个"不"，原样进哈希
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setRole(Role.OWNER);          // 注册只产 OWNER；客户端无法指定角色（DTO 里没这个字段）
        entity.setEnabled(true);
        LocalDateTime now = UtcTimes.nowUtc();   // 库列 DATETIME(3)，契约要求应用在事务内写，不用 CURRENT_TIMESTAMP
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        // id 留 null：自增列的值由数据库给，@Options(useGeneratedKeys) 会回填到 entity.id

        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 唯一性由 t_user.uk_user_login 判定，不是"先查再插"：先查再插在并发下会漏——
            // 两个请求同时查、都查不到、都往下插，第二个撞唯一键变成 500。
            // 顺带满足契约那条「409 不回显现有账户资料」：我们压根没查过库，没东西可回显。
            throw ApiException.of(ErrorCode.LOGIN_NAME_TAKEN)
                    .field(FieldErrors.alreadyExists("/loginName", "该登录名已被占用"));
        }
        return AuthRegisterResult.from(entity);
    }

    // ------------------------------------------------------------------ 登录

    @Transactional(readOnly = true)
    public AuthLoginResult login(AuthLoginRequest request) {
        String loginName = validateCredentials(request.loginName(), request.password());
        UserEntity entity = userMapper.findByLoginName(loginName);
        if (entity == null || !passwordEncoder.matches(request.password(), entity.getPasswordHash())) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED, "登录名或密码不正确");
        }
        if (!entity.isEnabled()) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED, "登录名或密码不正确");
        }
        Instant issuedAt = Instant.now();
        String accessToken = tokenIssuer.issue(entity.getId(), entity.getRole(), issuedAt);
        String expiresAt = AuthLoginResult.formatExpiresAt(tokenIssuer.expiresAt(issuedAt));
        return AuthLoginResult.of(accessToken, tokenIssuer.ttlSeconds(), expiresAt, entity);
    }

    // ------------------------------------------------------------------ 当前身份

    @Transactional(readOnly = true)
    public MeResult me(long userId) {
        // 每次请求都查库：令牌里的 role / enabled 只是副本，契约要求以**库中当前状态**为准。
        // 账号被禁用之后，之前签发的旧令牌必须失效，靠的就是这一次查询。
        UserEntity entity = userMapper.findById(userId);
        if (entity == null) {
            // 令牌签名合法，但账号已经不存在了（被删）。对客户端而言与"凭证失效"同类。
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (!entity.isEnabled()) {
            // 这条与登录那条不同：调用方手里已经握着这个账号的**合法令牌**，说明它已经证明过身份，
            // 此时说出"已禁用"不泄露额外信息。文案取 openapi 的 disabledAccount 样例。
            throw ApiException.of(ErrorCode.UNAUTHENTICATED, "账号已禁用");
        }
        return MeResult.from(entity);
    }

    // ------------------------------------------------------------------ 共用校验

    /**
     * 注册与登录共用的一套校验，返回**规范化后**的 loginName。
     *
     * <p>顺序是契约硬要求：「规范化为小写**之后**唯一」——所以必须**先用原值校验**
     * （否则 {@code ^[A-Za-z0-9_]{3,64}$} 这个字符集根本验不出大小写问题），再把小写形式交给落库/查询。
     * 两半拆到不同层里，中间隔一次方法调用就散了。
     *
     * <p>越界用 {@code OUT_OF_RANGE}、字符集用 {@code INVALID_FORMAT}：字段级子码是封闭表，
     * 长度不对和格式不对是两种不同的子码，不能混成一个。
     *
     * <p>原实现是 {@code public void} 让调用方自己再转一次小写——那等于把"校验"和"规范化"
     * 这两半又拆开了，而且公开出去的方法容易被别处当成"只校验"用。
     */
    private String validateCredentials(String rawLoginName, String rawPassword) {
        if (rawLoginName == null) {
            throw ApiException.invalid(FieldErrors.required("/loginName"));
        }
        if (rawPassword == null) {
            throw ApiException.invalid(FieldErrors.required("/password"));
        }
        if (rawLoginName.length() < 3 || rawLoginName.length() > 64) {
            throw ApiException.invalid(FieldErrors.outOfRange("/loginName", "必须为 3 至 64 个字符"));
        }
        if (!rawLoginName.matches("^[A-Za-z0-9_]{3,64}$")) {
            throw ApiException.invalid(FieldErrors.invalidFormat("/loginName", "只允许字母、数字与下划线"));
        }
        if (rawPassword.length() < 12 || rawPassword.length() > 128) {
            throw ApiException.invalid(FieldErrors.outOfRange("/password", "必须为 12 至 128 个字符"));
        }
        // Locale.ROOT：不用默认区域，否则土耳其语环境下 'I' 会变成 'ı'，同一个账号会算出两个名字
        return rawLoginName.toLowerCase(Locale.ROOT);
    }
}
