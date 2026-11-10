package com.autocare.server.web;

import com.autocare.common.error.ApiError;
import com.autocare.common.error.ApiErrorResponse;
import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 把错误信封**直接写进 response 的字节流**。
 *
 * <p><b>为什么不返一个对象、交给 Spring 去序列化？</b>因为 406 那条路走不通：
 * 406 的含义正是「客户端只接受它声明的媒体类型，我们不支持」。这种时候再让 Spring 做内容协商，
 * 它发现自己没法产出客户端要的类型，于是**不放 body**——信封就丢了。而契约要求
 * 406 也必须是一个完整的 JSON 信封。绕过协商、自己写字节，是唯一能保证"所有出口同一形状"的做法。
 *
 * <p>它同时负责两件不能漏的事：
 * <ul>
 *   <li>响应头 {@code X-Request-Id} 与 body 的 {@code requestId} 取**同一个值**（都来自进入时的过滤器）。</li>
 *   <li>{@code retryable=true} 的码带上 {@code Retry-After}（契约：429/503 建议重试时给秒数）。</li>
 * </ul>
 */
@Component
public class ApiErrorWriter {

    private static final String JSON_CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 业务异常入口。 */
    public void write(HttpServletRequest request, HttpServletResponse response, ApiException ex) throws IOException {
        write(request, response, ex.errorCode(), ex.getMessage(), ex.fieldErrors(), ex.details());
    }

    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      ErrorCode code,
                      String message,
                      List<FieldError> fieldErrors,
                      Map<String, Object> details) throws IOException {
        write(request, response, code.httpStatus(), new ApiError(code, message, code.retryable(), fieldErrors, details));
    }

    /**
     * 带<b>显式 HTTP 状态</b>的重载。
     *
     * <p>正常出口不用它——HTTP 状态由 {@code ErrorCode.httpStatus()} 唯一决定，只有一个真相来源。
     * 只有「状态码和业务码本来就不同源」的那一处才用它：**405 方法不对**。因为公共契约 §4 的错误目录
     * 是封闭的、没有 405 对应的业务码（ACCEPT-009 已裁：不新增码），所以那条路是
     * 「HTTP 405 + {@code error.code = INVALID_ARGUMENT}」——两个值必须分开给，否则
     * {@code INVALID_ARGUMENT} 会把状态压成 400（G0-04 r3 首跑实测）。
     */
    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      int httpStatus,
                      ErrorCode code,
                      String message,
                      List<FieldError> fieldErrors,
                      Map<String, Object> details) throws IOException {
        write(request, response, httpStatus, new ApiError(code, message, code.retryable(), fieldErrors, details));
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ApiError error) throws IOException {
        write(request, response, error.code().httpStatus(), error);
    }

    private void write(HttpServletRequest request,
                       HttpServletResponse response,
                       int httpStatus,
                       ApiError error) throws IOException {
        String requestId = resolveRequestId(request);
        byte[] body = objectMapper.writeValueAsBytes(new ApiErrorResponse(requestId, error));

        // 只丢缓冲区，不 reset()：reset() 会连响应头一起清掉，把编号过滤器设好的 X-Request-Id 也清没了
        response.resetBuffer();
        response.setStatus(httpStatus);
        response.setContentType(JSON_CONTENT_TYPE);
        // 显式带 charset。契约只规定媒体类型是 application/json，加 charset 仍然是它；
        // 而不带 charset 时 PowerShell 5.1 会按 Latin-1 解码（本项目踩过的老坑），
        // 求证时中文 message 全是乱码、得靠 -OutFile 落原始字节才看得清。加这一笔，取证链干净。
        response.setCharacterEncoding("UTF-8");
        response.setHeader(RequestIdFilter.HEADER_NAME, requestId);
        // 显式写成契约要的那一个值。不写的话 Spring 会给出一长串默认值
        // （no-cache, no-store, max-age=0, must-revalidate，实测见 G0-04 证据 53-鉴权-401-*），
        // 语义上够用，但形状跟契约声明的 no-store 不一致，验收对着响应头比对时会被记为不符。
        response.setHeader("Cache-Control", "no-store");
        if (error.code() == ErrorCode.UNAUTHENTICATED) {
            // 契约在 401 的响应定义里声明了 WWW-Authenticate: Bearer。
            // 401 有两条来路（过滤链拒的、业务层判的，见第 3 节的"出口"说明），
            // 它们在同一个写出点收口，所以补在这里——否则只会给其中一条加上、另一条漏掉。
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        if (error.retryable()) {
            response.setHeader("Retry-After", retryAfterSeconds(error));
        }
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /** 契约：可重试的响应带 Retry-After（秒）。调用方可通过 details 覆盖，默认 1。 */
    private static String retryAfterSeconds(ApiError error) {
        Object v = error.details().get("retryAfterSeconds");
        if (v instanceof Number n && n.intValue() > 0) {
            return String.valueOf(n.intValue());
        }
        return "1";
    }

    /** 取编号：正常情况下过滤器已经放过；万一为空则兜一个新值，保证头与体绝不为 null。 */
    private static String resolveRequestId(HttpServletRequest request) {
        String requestId = RequestIdFilter.currentRequestId(request);
        return (requestId != null && !requestId.isBlank()) ? requestId : java.util.UUID.randomUUID().toString();
    }
}
