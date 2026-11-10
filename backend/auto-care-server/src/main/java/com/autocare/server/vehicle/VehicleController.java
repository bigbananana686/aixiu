package com.autocare.server.vehicle;

import com.autocare.server.command.CommandResponses;
import com.autocare.server.web.ApiResponse;
import com.autocare.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {
    private final VehicleService vehicleService;
    public VehicleController(VehicleService vehicleService){
        this.vehicleService = vehicleService;
    }
    @PatchMapping("/{vehicleId}")
    public ResponseEntity<ApiResponse<JsonNode>> update(
            @PathVariable("vehicleId") String vehicleId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody VehicleUpdateRequest body,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        long actorId;
        try {
            actorId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return CommandResponses.toEntity(
                vehicleService.update(actorId, vehicleId, body, key,  RequestIdFilter.currentRequestId(request)),request
                );
    }
    @PostMapping
    public ResponseEntity<ApiResponse<JsonNode>> create(
            @RequestBody VehicleCreateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        long actorId;
        try{
            actorId = Long.parseLong(jwt.getSubject());
        }catch(NumberFormatException e){
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return CommandResponses.toEntity(
                vehicleService.create(actorId,body,key,RequestIdFilter.currentRequestId(request)),request);
    }
    @GetMapping
    public ResponseEntity<ApiResponse<VehicleListResult>> list(
            @RequestParam(value = "page", required = false) String rawPage,
            @RequestParam(value = "pageSize", required = false) String rawPageSize,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        long actorId;
        try{
            actorId = Long.parseLong(jwt.getSubject());
        }catch (NumberFormatException e){
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(request,
                vehicleService.list(actorId, rawPage, rawPageSize, request.getParameterMap().keySet())));
    }
    }
