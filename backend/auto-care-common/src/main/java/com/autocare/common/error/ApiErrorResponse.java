package com.autocare.common.error;

import java.util.Objects;

/**
 * 错误响应**整体**的形状：{@code {requestId, error}}。
 *
 * <p>它和成功响应 {@code {requestId, data}} 是一对：**两者都带同一个 requestId**，
 * 而且这个值必须与响应头 {@code X-Request-Id} 完全一致。它由请求进来时的那道过滤器生成，
 * 一路带到出口——所以错误信封能顺着编号在日志里找到对应的那一次请求。
 */
public record ApiErrorResponse(String requestId, ApiError error) {

    public ApiErrorResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(error, "error");
    }
}
