package com.autocare.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 信封里 {@code error} 这一整块：{@code {code, message, retryable, fieldErrors, details}}。
 *
 * <p>两个「永远不许是 null」的字段在这里就归位，而不是指望每个调用点都记得填：
 * <ul>
 *   <li>{@code fieldErrors}——**始终是数组**，没有内容就是 {@code []}，**不用 null 代替空集合**。</li>
 *   <li>{@code details}——始终是对象，没有内容就是 {@code {}}。</li>
 * </ul>
 * 这是契约的硬要求，也是这个类存在的意义：把"每次都记得填"变成"不可能填错"。
 */
public record ApiError(
        ErrorCode code,
        String message,
        boolean retryable,
        List<FieldError> fieldErrors,
        Map<String, Object> details) {

    public ApiError {
        Objects.requireNonNull(code, "code");
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
        // 不用 Map.copyOf：它连 null 值都不接受，而 details 里的业务上下文允许有 null（例如"尚无 approvedQuoteId"）
        details = (details == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        message = (message == null || message.isBlank()) ? code.defaultMessage() : message;
    }

    /** 用一个业务码造出「没有字段级细节」的标准错误。 */
    public static ApiError of(ErrorCode code) {
        return new ApiError(code, code.defaultMessage(), code.retryable(), List.of(), Map.of());
    }
}
