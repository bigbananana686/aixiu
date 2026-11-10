package com.autocare.server.auth;

import com.autocare.server.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 身份接口的入口层。**这一层刻意保持极薄，一个 if 都没有**——只做三件纯搬运的事：
 * 把 JSON 收成请求对象、交给 service、把结果包成带编号的成功响应。
 *
 * <p>为什么这里不做校验：一有判断它就变成第二份业务逻辑了。格式规则放 controller，
 * 将来 ADMIN 建号要用同一套规则时就得复制一遍；而且契约那条「规范化为小写**之后**唯一」
 * 要求"用原值校验、用小写落库"这两半挨着，拆到两层中间隔一次调用就散了。
 *
 * <p>三个响应头各有出处：
 * <ul>
 *   <li>{@code Location: /api/v1/me}——契约规定注册成功的 Location 指向新身份的可读资源；登录不带它。</li>
 *   <li>{@code Cache-Control: no-store}——契约在 201 与 200 上都声明了：私有数据与令牌不得被任何共享缓存保存。</li>
 *   <li>{@code X-Request-Id}——由最外层的编号过滤器统一设置，这里不重复设。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthRegisterResult>> register(
            HttpServletRequest request,
            @RequestBody AuthRegisterRequest body) {

        AuthRegisterResult data = authService.register(body);

        return ResponseEntity
                .created(URI.create("/api/v1/me"))
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(request, data));
    }

    /**
     * 登录。**令牌只出现在响应体里**：不进 URL、不进日志、不进响应头。
     *
     * <p>重复登录可能签发新令牌，所以这个接口**不承诺相同响应**，也不提供幂等重放——
     * 这也是它带着 Idempotency-Key 会被 400 拒绝的原因（过滤器在更外层拦）。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthLoginResult>> login(
            HttpServletRequest request,
            @RequestBody AuthLoginRequest body) {

        AuthLoginResult data = authService.login(body);

        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(request, data));
    }
}
