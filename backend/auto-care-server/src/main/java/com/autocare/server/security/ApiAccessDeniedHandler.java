package com.autocare.server.security;

import com.autocare.common.error.ErrorCode;
import com.autocare.server.web.ApiErrorWriter;
import com.autocare.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 403 的翻译器：**已认证，但这个角色不允许调用这个 operation**。
 *
 * <p>它与 401 是两条不同的路，别合并：
 * <ul>
 *   <li>401 = 我们不知道你是谁（凭证缺失/失效/账号禁用）→ 去重新登录。</li>
 *   <li>403 = 我们知道你是谁，但你没这个权限 → 重新登录没用。</li>
 * </ul>
 * 契约把这两种情况分得很清楚，而且要遵守同一条判定顺序：先 401、再 403、最后才是"对象不存在/不可见"的 404。
 *
 * <p>响应体不返回任何资源细节——403 里出现订单号、车主信息就属于越权泄露。
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessDeniedHandler.class);

    private final ApiErrorWriter writer;

    public ApiAccessDeniedHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.info("角色不足 requestId={}", RequestIdFilter.currentRequestId(request));
        writer.write(request, response, ErrorCode.FORBIDDEN, null, List.of(), Map.of());
    }
}
