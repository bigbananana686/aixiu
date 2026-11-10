package com.autocare.server.order.quote;

/** 发布后不可变；金额两位小数字符串，工时由服务端目录快照计算。 */
public record QuoteItemSnapshot(
  String lineKey,
  String serviceItemId,
  String nameSnapshot,
  String unitPrice,
  String currency,
  int quantity,
  int durationMinutes,
  int lineMinutes,
  boolean isExtra
) {}
