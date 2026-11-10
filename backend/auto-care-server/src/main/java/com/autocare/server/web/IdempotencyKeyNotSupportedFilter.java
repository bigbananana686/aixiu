package com.autocare.server.web;

import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拒绝不该出现的幂等键。
 *
 * <p>幂等键分四类用法（公共契约 §5.1），本片只涉及其中一类：
 * <b>NOT_SUPPORTED_AUTH</b> —— 注册与登录**不接受**幂等键，携带就返回 400。
 * 理由：注册的重复由 loginName 唯一性兜住（409），而登录每次都可能签发新令牌，
 * 本来就不承诺"同样的响应"，套一层幂等缓存反而会骗客户端。
 *
 * <p>这类校验放过滤器的原因和大小闸门一样：它是**请求头层面**的准入，
 * 跟业务无关。放进 Controller 会让那一层出现第一个判断，开始长成第二份业务逻辑。
 *
 * <p>契约要求的形状很具体，别写简了：整体 message 是「请求头不合法」，
 * fieldErrors 的字段路径是 {@code /headers/Idempotency-Key}（**请求头路径这么写**），
 * 子码是 {@code NOT_SUPPORTED}（不是业务码）。
 */
@Component
@Order(IdempotencyKeyNotSupportedFilter.FILTER_ORDER)
public class IdempotencyKeyNotSupportedFilter extends OncePerRequestFilter {

    /** 排在编号（-200）、大小闸门（-190）之后，安全链（-100）之前。 */
    public static final int FILTER_ORDER = -180;

    private static final String HEADER_NAME = "Idempotency-Key";

    /** 只覆盖两个 PUBLIC 入口；其余 operation 将来才接幂等键。 */
    private static final Set<String> NOT_SUPPORTED_PATHS =
            Set.of("/api/v1/auth/register", "/api/v1/auth/login");

    private final ApiErrorWriter writer;

    public IdempotencyKeyNotSupportedFilter(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (NOT_SUPPORTED_PATHS.contains(uri) && request.getHeader(HEADER_NAME) != null) {
            writer.write(request, response, ErrorCode.INVALID_ARGUMENT, "请求头不合法",
                    List.of(FieldErrors.notSupported(FieldErrors.headerPath(HEADER_NAME), "该操作不接受幂等键")),
                    Map.of());
            return;
        }
        chain.doFilter(request, response);
    }
}
