package com.autocare.common.error;

public enum ErrorCode {
    INVALID_ARGUMENT(400, false, "请求字段不合法"),
    UNAUTHENTICATED(401, false, "未认证或凭证已失效，请重新登录"),
    FORBIDDEN(403, false, "当前角色不允许执行该操作"),
    RESOURCE_NOT_FOUND(404, false, "资源不存在或不可见"),
    LOGIN_NAME_TAKEN(409, false, "该登录名已被占用"),
    ORDER_STATE_CONFLICT(409, false, "当前订单状态不允许该动作"),
    ORDER_VERSION_CONFLICT(409, false, "订单版本已变化，请刷新后重试"),
    RESOURCE_VERSION_CONFLICT(409, false, "资源版本已变化，请刷新后重试"),
    QUOTE_VERSION_CONFLICT(409, false, "报价基线已变化，请刷新后重试"),
    QUOTE_EXPIRED(409, false, "该报价已过确认截止时间"),
    ORDER_ACCEPT_EXPIRED(409, false, "已超过接单截止时间"),
    QUOTE_STATE_CONFLICT(409, false, "该报价已进入终态，不能再次审批"),
    SLOT_CONFLICT(409, false, "该时段已被占用，请另选时间"),
    WORK_WINDOW_CONFLICT(409, false, "该修改会使已有预约落在工作窗口之外"),
    WORK_WINDOW_VIOLATION(409, false, "所选时间不在技师工作窗口内"),
    SERVICE_TIME_INSUFFICIENT(409, false, "剩余时间不足以完成已批准工时，请先申请延期"),
    CATALOG_VERSION_CONFLICT(409, false, "服务目录已更新，请重新选择"),
    SERVICE_ITEM_UNAVAILABLE(409, false, "该服务项目当前不可用或该技师不支持"),
    IDEMPOTENCY_KEY_REUSED(409, false, "该幂等键已用于内容不同的请求"),
    JOB_STATE_CONFLICT(409, false, "只有 FAILED 的任务可以人工重试"),
    JOB_NOT_RETRYABLE(409, false, "任务的发起人已失去该资源权限，需重新发起业务请求"),
    PAYLOAD_TOO_LARGE(413, false, "请求体超过允许大小"),
    UNSUPPORTED_MEDIA_TYPE(415, false, "不支持的媒体类型，请使用 application/json"),
    NOT_ACCEPTABLE(406, false, "无法满足 Accept 协商"),
    RATE_LIMITED(429, true, "请求过于频繁，请稍后重试"),
    AI_QUEUE_FULL(429, true, "AI 任务容量已满，请稍后重试"),
    AI_UNAVAILABLE(503, true, "模型服务暂时不可用，请稍后重试或手工填写表单"),
    RETRYABLE_STORAGE_ERROR(503, true, "存储暂时不可用，请用同一幂等键重试"),
    INTERNAL_ERROR(500, false, "服务器内部错误");

    private final int httpStatus;
    private final boolean retryable;
    private final String defaultMessage;

    ErrorCode(int httpStatus, boolean retryable, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
