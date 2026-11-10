package com.autocare.common.error;

import java.util.Objects;

/**
 * 信封里 {@code error.fieldErrors[]} 的一项：一条**字段级**问题。
 *
 * <p>三个字段都必填（契约定死了 {field, code, message} 这个形状）：
 * <ul>
 *   <li>{@code field}——JSON Pointer 形式，例如 {@code /items/0/quantity}；
 *       请求头写 {@code /headers/Idempotency-Key}；数组中用下标定位。</li>
 *   <li>{@code code}——{@link FieldErrorCode}，**不是**业务码。</li>
 *   <li>{@code message}——面向人的短说明。</li>
 * </ul>
 */
public record FieldError(String field, FieldErrorCode code, String message) {

    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
