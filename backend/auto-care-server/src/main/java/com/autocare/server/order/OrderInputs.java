package com.autocare.server.order;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.FieldErrors;
import tools.jackson.databind.JsonNode;

public final class OrderInputs {
    private OrderInputs() {}

    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw ApiException.invalid(FieldErrors.invalidFormat("/orderId", "必须为无前导零的正整数"));
        }
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) {
            throw ApiException.invalid(FieldErrors.outOfRange("/orderId", "ID 超出范围"));
        }
    }

    public static int version(JsonNode node, boolean present) {
        String path = "/expectedRowVersion";
        require(node, present, path);
        String value = node.stringValue();
        if ("0".equals(value)) {
            throw ApiException.invalid(FieldErrors.outOfRange(path, "版本从 1 开始"));
        }
        if (!value.matches("[1-9][0-9]{0,9}")) {
            throw ApiException.invalid(FieldErrors.invalidFormat(path, "必须为无前导零的正整数"));
        }
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) {
            throw ApiException.invalid(FieldErrors.outOfRange(path, "版本不得超过 2147483647"));
        }
    }

    public static String reason(JsonNode node, boolean present) {
        require(node, present, "/reason");
        String value = node.stringValue();
        // 先拒绝控制字符，避免 trim 把恶意控制字符悄悄吞掉。
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalid(FieldErrors.invalidFormat("/reason", "不得含控制字符"));
        }
        value = value.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 500) {
            throw ApiException.invalid(FieldErrors.outOfRange("/reason", "去除首尾空白后必须为 1—500 字符"));
        }
        return value;
    }

    private static void require(JsonNode node, boolean present, String path) {
        if (!present) throw ApiException.invalid(FieldErrors.required(path));
        if (node == null || node.isNull()) throw ApiException.invalid(FieldErrors.nullNotAllowed(path));
        if (!node.isString()) throw ApiException.invalid(FieldErrors.invalidType(path, "必须为字符串"));
    }
}
