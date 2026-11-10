package com.autocare.server.task;

/** 一次领取的凭证；同一 worker 重新领取也必须换 token。 */
public record JobLease(long jobId, String claimToken) {}
