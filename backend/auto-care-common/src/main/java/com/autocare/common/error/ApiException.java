package com.autocare.common.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 业务代码抛这个，表示「这次请求该以某个业务错误码结束」。
 *
 * <p><b>它和普通异常的分工</b>：这不是故障，是**预期内**的结果——字段不合法、口令不对、版本过时
 * 都属于"正常的失败路径"，只是不能算成功。所以它必须携带一个 {@link ErrorCode}
 * （决定 HTTP 状态与 code），而不是靠消息文本去猜。真正的故障（数据库连不上、代码写崩了）
 * 走普通异常，由全局处理器兜成 500 INTERNAL_ERROR，绝不冒充成版本冲突或资源冲突。
 *
 * <p><b>典型用法</b>（链式，读起来就是"什么码 + 哪几个字段"）：
 * <pre>{@code
 * throw ApiException.invalid("请求字段不合法")
 *         .field("/role", FieldErrorCode.UNKNOWN_FIELD, "不接受的字段，注册只能创建 OWNER");
 * }</pre>
 *
 * <p>字段级错误用可变列表收集、抛出即定型，是为了让调用点一次写完多个字段问题；
 * 对外用 {@link #fieldErrors()} / {@link #details()} 暴露的是只读视图。
 */
@SuppressWarnings("serial")
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldError> fieldErrors = new ArrayList<>();
    private final Map<String, Object> details = new LinkedHashMap<>();

    public ApiException(ErrorCode code) {
        this(code, null);
    }

    public ApiException(ErrorCode code, String message) {
        super((message == null || message.isBlank())
                ? Objects.requireNonNull(code, "code").defaultMessage()
                : message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** 追加一条字段级错误。 */
    public ApiException field(FieldError fieldError) {
        fieldErrors.add(Objects.requireNonNull(fieldError, "fieldError"));
        return this;
    }

    /** 追加一条字段级错误（简写）。 */
    public ApiException field(String field, FieldErrorCode fieldCode, String message) {
        return field(new FieldError(field, fieldCode, message));
    }

    /** 追加一条安全的业务上下文（如 currentState）。**不要放他人资料、SQL、堆栈。** */
    public ApiException detail(String key, Object value) {
        details.put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    public ErrorCode errorCode() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return Collections.unmodifiableList(fieldErrors);
    }

    public Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }

    /** 转成信封里 {@code error} 那一块。 */
    public ApiError toApiError() {
        return new ApiError(code, getMessage(), code.retryable(), fieldErrors, details);
    }

    // ------------------------------------------------------------------ 常用快捷构造

    public static ApiException of(ErrorCode code) {
        return new ApiException(code);
    }

    public static ApiException of(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    /** 400：字段不合法，带若干字段级问题。 */
    public static ApiException invalid(FieldError... fields) {
        return invalid(null, fields);
    }

    /** 400：字段不合法，指定整体 message。 */
    public static ApiException invalid(String message, FieldError... fields) {
        ApiException ex = new ApiException(ErrorCode.INVALID_ARGUMENT, message);
        for (FieldError f : fields) {
            ex.field(f);
        }
        return ex;
    }

    /** 500：未预料故障。message 由处理器统一成不含内部细节的说法。 */
    public static ApiException internal() {
        return new ApiException(ErrorCode.INTERNAL_ERROR);
    }
}
