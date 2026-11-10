package com.autocare.server.command;

import com.autocare.server.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * 把 {@link CommandResult} 拼成 HTTP 响应。执行器与 controller 之间的最后一道胶水。
 *
 * <p><b>为什么要集中成一个方法</b>：重放要比普通成功多两个响应头，而这两个头是契约硬要求
 * （{@code Idempotency-Replayed: true}、{@code Original-Request-Id}）。散在每个 controller 里写，
 * 早晚会有某个接口漏掉——漏了不会报错，只会让客户端以为这是一次新执行。放一处，漏不掉。
 *
 * <p>响应体里的 {@code requestId} 用<b>本次</b>请求的编号，不是原请求的。
 * 契约原文：「原 response 含原 requestId 用于审计，传输时替换为本次有效 requestId」。
 * 原编号去 {@code Original-Request-Id} 头里找。
 */
public final class CommandResponses {

    /** 契约给的重放标记头。值固定 {@code true}（字符串，不是布尔）。 */
    public static final String HEADER_REPLAYED = "Idempotency-Replayed";

    /** 契约给的原请求头。值 = 原请求的 requestId（小写 UUID）。 */
    public static final String HEADER_ORIGINAL_REQUEST_ID = "Original-Request-Id";

    private CommandResponses() {
    }

    public static ResponseEntity<ApiResponse<JsonNode>> toEntity(CommandResult result, HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
                // 命令响应带的是私有数据，契约要求 no-store
                .cacheControl(CacheControl.noStore());

        if (result.location() != null) {
            // 原样回传存的相对路径；不在这里拼接、不做域名补全
            builder.location(URI.create(result.location()));
        }
        if (result.replayed()) {
            builder.header(HEADER_REPLAYED, "true");
            if (result.originalRequestId() != null) {
                builder.header(HEADER_ORIGINAL_REQUEST_ID, result.originalRequestId());
            }
        }
        return builder.body(ApiResponse.of(request, result.data()));
    }
}
