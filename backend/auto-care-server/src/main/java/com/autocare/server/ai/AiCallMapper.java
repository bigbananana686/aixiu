package com.autocare.server.ai;

import org.apache.ibatis.annotations.*;

@Mapper
public interface AiCallMapper {
  @Insert(
    "INSERT INTO t_ai_call_log(scene,actor_id,input_hash,prompt_version,model_id,document_versions,outcome,latency_ms,prompt_tokens,completion_tokens,total_tokens,error_code,created_at) VALUES('BOOKING_DRAFT',#{actorId},#{inputHash},'booking-draft-v1',#{modelId},'[]',#{outcome},#{latencyMs},#{promptTokens},#{completionTokens},#{totalTokens},#{errorCode},UTC_TIMESTAMP(3))"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(AiCallRow row);
}
