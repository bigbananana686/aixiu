package com.autocare.server.auth;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.server.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me}：读取当前有效身份。OWNER / TECH / ADMIN 三个角色都允许调用。
 *
 * <p><b>身份只来自「已验证令牌 + 账号状态核验」</b>，不接受任何客户端自报的 userId / role
 * 字段或请求头。这里取的是令牌的 {@code sub}——它已经过签名、iss、aud、exp、nbf 校验，
 * 校验和过期都轮不到这里判：不合格的令牌在安全过滤链就被拒了，请求根本进不来。
 *
 * <p>本方法**不改变任何业务状态**（GET），也不读写幂等记录（NOT_APPLICABLE_READ）。
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResult>> me(
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        long userId;
        try {
            // sub 是我们自己签的十进制 userId。理论上不会失败；真失败说明令牌来自别处，按凭证失效处理。
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }

        MeResult data = authService.me(userId);

        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(request, data));
    }
}
