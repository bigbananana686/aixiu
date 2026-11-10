package com.autocare.server.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * 成功响应的形状：{@code {requestId, data}}。与错误信封 {@code {requestId, error}} 是一对。
 *
 * <p>两个要点：
 * <ul>
 *   <li>它的 {@code requestId} 必须与响应头 {@code X-Request-Id} 是**同一个值**——
 *       和错误那条一样，取自进入时的过滤器。做法集中在 {@link #of} 一处，
 *       不让三个接口各写一遍。</li>
 *   <li>{@code data} 为 null 时**不要**输出成 {@code "data":null} 以外的东西：
 *       契约要求成功响应都有 body，不用 204，为的就是能保存"成功重放"的原结果。</li>
 * </ul>
 */
public record ApiResponse<T>(String requestId, T data) {

    /** 用当前请求的编号装配成功响应。 */
    public static <T> ApiResponse<T> of(HttpServletRequest request, T data) {
        String requestId = RequestIdFilter.currentRequestId(request);
        return new ApiResponse<>(requestId != null ? requestId : UUID.randomUUID().toString(), data);
    }
}
