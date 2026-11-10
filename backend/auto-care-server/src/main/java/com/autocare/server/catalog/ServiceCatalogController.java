package com.autocare.server.catalog;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/service-items（契约 doc10 §4.4）。
 *
 * <p>与 {@code VehicleController.list} 同款形状，两处刻意不同：
 * <ul>
 *   <li><b>不声明任何 Idempotency-Key 参数</b>：本接口是 {@code NOT_APPLICABLE_READ}——
 *       客户端带键也不产生保障、不落记录，实现方式就是代码根本不碰它。</li>
 *   <li><b>没有角色闸门在 SecurityConfig</b>：三角色（OWNER/TECH/ADMIN）都可读，
 *       401 由安全链兜住，403 兜底分支在 service 里。</li>
 * </ul>
 *
 * <p>query 参数只声明 page/pageSize；其余参数由 service 主动点名拒绝（D-1 口径）——
 * Spring 对未声明参数是静默忽略的，不点名就等于默许（G1-02a N-06 的教训）。
 */
@RestController
@RequestMapping("/api/v1/service-items")
public class ServiceCatalogController {

    private final ServiceCatalogService serviceCatalogService;

    public ServiceCatalogController(ServiceCatalogService serviceCatalogService) {
        this.serviceCatalogService = serviceCatalogService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ServiceItemListResult>> list(
            @RequestParam(value = "page", required = false) String rawPage,
            @RequestParam(value = "pageSize", required = false) String rawPageSize,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        long actorId;
        try {
            actorId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(request, serviceCatalogService.list(
                        actorId, rawPage, rawPageSize, request.getParameterMap().keySet())));
    }
}
