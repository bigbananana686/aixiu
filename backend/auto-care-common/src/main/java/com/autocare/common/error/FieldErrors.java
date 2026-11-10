package com.autocare.common.error;

import java.util.Objects;

/**
 * 造 {@link FieldError} 的工厂。目的只有一个：让调用点不必每次都手写子码 + 默认说明，
 * 从而减少「子码拼错」和「忘了填 message」这两类低级错误。
 *
 * <p>每个子码一个方法，方法名就是子码名（小驼峰）。需要自定义说明时用带 message 的重载。
 */
public final class FieldErrors {

    private FieldErrors() {
    }

    /** 通用入口。 */
    public static FieldError of(String field, FieldErrorCode code, String message) {
        return new FieldError(field, code, message);
    }

    /** 必填缺失。 */
    public static FieldError required(String field) {
        return of(field, FieldErrorCode.REQUIRED, FieldErrorCode.REQUIRED.defaultMessage());
    }

    public static FieldError required(String field, String message) {
        return of(field, FieldErrorCode.REQUIRED, message);
    }

    /** 显式传了 null，而契约不允许。 */
    public static FieldError nullNotAllowed(String field) {
        return of(field, FieldErrorCode.NULL_NOT_ALLOWED, FieldErrorCode.NULL_NOT_ALLOWED.defaultMessage());
    }

    /** schema 里没有这个字段（注册传 role、确认传 items）。 */
    public static FieldError unknownField(String field, String message) {
        return of(field, FieldErrorCode.UNKNOWN_FIELD, message);
    }

    /** 该字段在此 operation 不被接受（登录携带幂等键）。 */
    public static FieldError notSupported(String field, String message) {
        return of(field, FieldErrorCode.NOT_SUPPORTED, message);
    }

    public static FieldError invalidFormat(String field, String message) {
        return of(field, FieldErrorCode.INVALID_FORMAT, message);
    }

    public static FieldError invalidType(String field, String message) {
        return of(field, FieldErrorCode.INVALID_TYPE, message);
    }

    public static FieldError outOfRange(String field, String message) {
        return of(field, FieldErrorCode.OUT_OF_RANGE, message);
    }

    public static FieldError duplicate(String field, String message) {
        return of(field, FieldErrorCode.DUPLICATE, message);
    }

    public static FieldError alreadyExists(String field, String message) {
        return of(field, FieldErrorCode.ALREADY_EXISTS, message);
    }

    public static FieldError notAvailable(String field, String message) {
        return of(field, FieldErrorCode.NOT_AVAILABLE, message);
    }

    public static FieldError versionMismatch(String field, String message) {
        return of(field, FieldErrorCode.VERSION_MISMATCH, message);
    }

    public static FieldError baselineMismatch(String field, String message) {
        return of(field, FieldErrorCode.BASELINE_MISMATCH, message);
    }

    public static FieldError conflict(String field, String message) {
        return of(field, FieldErrorCode.CONFLICT, message);
    }

    public static FieldError notAllowed(String field, String message) {
        return of(field, FieldErrorCode.NOT_ALLOWED, message);
    }

    /** 请求头路径的写法：契约规定请求头字段路径写 {@code /headers/<名字>}。 */
    public static String headerPath(String headerName) {
        return "/headers/" + Objects.requireNonNull(headerName, "headerName");
    }
}
