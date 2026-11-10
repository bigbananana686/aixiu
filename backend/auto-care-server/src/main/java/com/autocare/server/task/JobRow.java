package com.autocare.server.task;

import java.time.LocalDateTime;

/** 内部持久化投影，payload 和租约凭证不直接暴露到 HTTP。 */
public class JobRow {

  public Long id, actorId, resourceId;
  public String type, businessKey, payload, status, leaseOwner, claimToken;
  public String lastErrorCode, lastErrorMessage;
  public Boolean lastErrorRetryable;
  public int attempts, retryGeneration;
  public LocalDateTime nextRunAt, leaseUntil, createdAt, updatedAt;
}
