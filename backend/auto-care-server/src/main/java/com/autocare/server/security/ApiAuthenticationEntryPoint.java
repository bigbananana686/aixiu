package com.autocare.server.security;

import com.autocare.common.error.ErrorCode;
import com.autocare.server.web.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 401 的翻译器——**这是"过滤链里的异常"那条出口的接住点**。
 *
 * <p>为什么不能用 {@code @RestControllerAdvice} 接：advice 是 Spring MVC 的组件，住在
 * DispatcherServlet 里面；而"没带凭证"是在安全过滤链（-100）就被拒的，请求压根没进到 MVC。
 * 所以框架默认只给你一个 {@code 401} + {@code Content-Length: 0} 的空响应
 * （本片第 2 节的证据里就是那个样子）。要让它变成项目信封，只能挂在安全链自己的钩子上。
 *
 * <p>message 传 null，于是用 {@link ErrorCode#UNAUTHENTICATED} 的默认话术
 * 「未认证或凭证已失效，请重新登录」——与契约 §3.3 的失败样例一致。
 * 不区分"没带 token / token 过期 / token 无效 / 签名不对"，**这几种必须同形**。
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);

    private final ApiErrorWriter writer;

    public ApiAuthenticationEntryPoint(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 只记类型，不记凭证内容
        log.info("未认证 requestId={} 原因类型={}",
                com.autocare.server.web.RequestIdFilter.currentRequestId(request),
                authException.getClass().getSimpleName());
        writer.write(request, response, ErrorCode.UNAUTHENTICATED, null, List.of(), Map.of());
    }
}
