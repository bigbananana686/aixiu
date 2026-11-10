package com.autocare.server.ai;

public class AiCallRow {

  public Long id, actorId;
  public String inputHash, modelId, outcome, errorCode;
  public Integer latencyMs, promptTokens, completionTokens, totalTokens;
}
