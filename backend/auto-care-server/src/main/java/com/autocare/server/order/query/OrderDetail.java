package com.autocare.server.order.query;

import com.autocare.server.order.BookingItemSnapshot;
import com.autocare.server.order.RulesSnapshot;
import com.autocare.server.order.lifecycle.OrderOperationEntry;
import com.autocare.server.order.lifecycle.OrderStateLogEntry;
import com.autocare.server.order.quote.QuoteItemSnapshot;
import java.util.List;

/**
 * {@code createOrder} 的响应体（契约 §2.4 {@code OrderDetail} ＋ §5.1 样例）。
 *
 * <p>三条「尚无」的口径（ACCEPT-004 Q-04 的既定裁决）：可空标量尚无时为 {@code null}
 * （approvedQuoteId／approvedQuoteVersion／pendingQuoteId／actualStart／actualFinish／serviceResult），
 * 集合尚无时为<b>空数组</b>（approvedItems／operations）——不用「可空对象引用」内嵌整版报价。
 */
public record OrderDetail(
  String orderId,
  String orderNo,
  String state,
  String rowVersion,
  String ownerId,
  String vehicleId,
  String technicianId,
  String serviceStart,
  String occupiedStart,
  String committedEnd,
  String effectiveEnd,
  String approvedQuoteId,
  String approvedQuoteVersion,
  List<QuoteItemSnapshot> approvedItems,
  String pendingQuoteId,
  String acceptDeadline,
  String address,
  String modelCode,
  String plateMasked,
  int mileageSnapshot,
  List<BookingItemSnapshot> bookingItems,
  RulesSnapshot rulesSnapshot,
  String actualStart,
  String actualFinish,
  String serviceResult,
  List<OrderOperationEntry> operations,
  List<OrderStateLogEntry> stateLog,
  String createdAt,
  String updatedAt,
  List<java.util.Map<String, Object>> exceptions
) {}
