package com.autocare.ai.booking;

import java.time.Instant;

public interface BookingDraftParser {
  Parsed parse(String text, Instant now, String timeZone);

  record Parsed(
    BookingDraft draft,
    String modelId,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens
  ) {}
}
