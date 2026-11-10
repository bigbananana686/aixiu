package com.autocare.common.error;

public enum FieldErrorCode {
    REQUIRED("必填字段缺失（含必需的 Idempotency-Key 与显式要求的可空字段）"),
    UNKNOWN_FIELD("提交了 schema 未定义的字段（如注册传 role、确认传 items）"),
    NOT_SUPPORTED("该字段在此 operation 不被接受（如登录携带幂等键）"),
    INVALID_FORMAT("形式不合法（前导零版本、非法日期、非法幂等键字符集）"),
    INVALID_TYPE("JSON 类型不对（ID/版本写成数值、布尔写成字符串）"),
    OUT_OF_RANGE("取值越界（quantity 0、mileage 负数、页码超限）"),
    NULL_NOT_ALLOWED("对该字段显式传了 null，而契约不允许"),
    DUPLICATE("数组内重复（同一 serviceItemId、同版 lineKey）"),
    ALREADY_EXISTS("唯一性冲突（loginName 已占用）"),
    NOT_AVAILABLE("目标不可用（目录项停用或技师不支持）"),
    VERSION_MISMATCH("期望版本与当前不一致"),
    BASELINE_MISMATCH("报价批准/待确认基线与当前不一致"),
    CONFLICT("该字段与已存在的记录冲突（幂等键已用于不同内容）"),
    NOT_ALLOWED("该筛选/字段在此场景被禁止（用他人身份做筛选）");

    private final String defaultMessage;

    FieldErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

