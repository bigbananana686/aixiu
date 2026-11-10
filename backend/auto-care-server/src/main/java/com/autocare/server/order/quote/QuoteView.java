package com.autocare.server.order.quote;

import com.autocare.server.order.lifecycle.OrderLifecycleService;
import com.autocare.server.web.UtcTimes;
import java.util.List;

public record QuoteView(
  String quoteId,
  String orderId,
  String version,
  String purpose,
  String baseApprovedQuoteId,
  String status,
  String totalAmount,
  String currency,
  int totalMinutes,
  int addedMinutes,
  String targetEnd,
  String expiresAt,
  String submittedBy,
  String submittedAt,
  String confirmedBy,
  String confirmedAt,
  String terminalReason,
  String contentHash,
  List<QuoteItemSnapshot> items,
  com.autocare.server.analysis.AnalysisResults.Summary analysis
) {
  public QuoteView withAnalysis(
    com.autocare.server.analysis.AnalysisResults.Summary summary
  ) {
    return new QuoteView(
      quoteId,
      orderId,
      version,
      purpose,
      baseApprovedQuoteId,
      status,
      totalAmount,
      currency,
      totalMinutes,
      addedMinutes,
      targetEnd,
      expiresAt,
      submittedBy,
      submittedAt,
      confirmedBy,
      confirmedAt,
      terminalReason,
      contentHash,
      items,
      summary
    );
  }

  static QuoteView from(QuoteRow r, List<QuoteItemSnapshot> items) {
    return new QuoteView(
      "" + r.getId(),
      "" + r.getOrderId(),
      "" + r.getVersion(),
      r.getPurpose(),
      OrderLifecycleService.nullableId(r.getBaseApprovedQuoteId()),
      r.getStatus(),
      r.getTotalAmount().setScale(2).toPlainString(),
      r.getCurrency(),
      r.getTotalMinutes(),
      r.getAddedMinutes(),
      UtcTimes.format(r.getTargetEnd()),
      UtcTimes.format(r.getExpiresAt()),
      "" + r.getSubmittedBy(),
      UtcTimes.format(r.getSubmittedAt()),
      OrderLifecycleService.nullableId(r.getConfirmedBy()),
      UtcTimes.format(r.getConfirmedAt()),
      r.getTerminalReason(),
      r.getContentHash(),
      items,
      null
    );
  }
}
