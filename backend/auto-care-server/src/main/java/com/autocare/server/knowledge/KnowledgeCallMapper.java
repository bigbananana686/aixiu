package com.autocare.server.knowledge;

import org.apache.ibatis.annotations.*;

@Mapper
public interface KnowledgeCallMapper {
  @Insert(
    "INSERT INTO t_ai_call_log(scene,actor_id,input_hash,prompt_version,model_id,document_versions,outcome,latency_ms,error_code,created_at) VALUES('KNOWLEDGE_INDEX',#{actor},#{hash},#{strategy},#{model},#{documents},#{outcome},#{latency},#{error},UTC_TIMESTAMP(3))"
  )
  int insert(
    @Param("actor") long actor,
    @Param("hash") String hash,
    @Param("strategy") String strategy,
    @Param("model") String model,
    @Param("documents") String documents,
    @Param("outcome") String outcome,
    @Param("latency") long latency,
    @Param("error") String error
  );
}
