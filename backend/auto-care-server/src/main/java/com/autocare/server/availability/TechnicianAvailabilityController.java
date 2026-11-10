package com.autocare.server.availability;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.server.web.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 技师可用时段（只读提示）。查询参数只声明这三个——**没声明的 query 参数 Spring 会静默忽略**，
 * 所以「额外参数 → 400」由 service 拿着 {@code parameterMap} 的键集自己查（与两个列表接口同一口径）。
 *
 * <p>本接口是 {@code NOT_APPLICABLE_READ}：不声明 {@code Idempotency-Key} 参数，
 * 客户端带不带键都只是一次普通读取。
 */
@RestController
@RequestMapping("/api/v1/technicians")
public class TechnicianAvailabilityController {

    private final TechnicianAvailabilityService technicianAvailabilityService;

    public TechnicianAvailabilityController(TechnicianAvailabilityService technicianAvailabilityService) {
        this.technicianAvailabilityService = technicianAvailabilityService;
    }

    @GetMapping("/availability")
    public ResponseEntity<ApiResponse<TechnicianAvailabilityResult>> availability(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "serviceItemIds", required = false) List<String> serviceItemIds,
            @RequestParam(value = "technicianId", required = false) String technicianId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        long actorId;
        try {
            actorId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(request,
                technicianAvailabilityService.availability(
                        actorId, date, serviceItemIds, technicianId, request.getParameterMap().keySet())));
    }
}
