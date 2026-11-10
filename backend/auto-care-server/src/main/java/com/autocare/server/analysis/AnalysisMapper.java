package com.autocare.server.analysis;

import com.autocare.server.task.JobRow;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AnalysisMapper {
  @Insert(
    "INSERT INTO t_quote_analysis_result(job_id,quote_id,quote_version,content_hash,findings,missing_evidence,citations,limitations,document_versions,analyzed_at,created_at) VALUES(#{jobId},#{quoteId},#{quoteVersion},#{contentHash},#{findings},#{missingEvidence},#{citations},#{limitations},#{documentVersions},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"
  )
  int insert(AnalysisRow row);

  @Select(
    "SELECT job_id,quote_id,quote_version,content_hash,findings,missing_evidence,citations,limitations,document_versions,analyzed_at FROM t_quote_analysis_result WHERE job_id=#{id}"
  )
  AnalysisRow result(long id);

  @Select(
    "SELECT id,type,business_key,actor_id,resource_id,payload,status,attempts,retry_generation,created_at,updated_at FROM t_background_job WHERE type='QUOTE_ANALYSIS' AND actor_id=#{actor} AND resource_id=#{quote} ORDER BY id DESC LIMIT 1"
  )
  JobRow latest(@Param("actor") long actor, @Param("quote") long quote);

  @Insert(
    "INSERT INTO t_ai_call_log(scene,actor_id,order_id,quote_id,input_hash,prompt_version,model_id,document_versions,outcome,latency_ms,prompt_tokens,completion_tokens,total_tokens,error_code,created_at) VALUES('QUOTE_ANALYSIS',#{actor},#{order},#{quote},#{hash},'quote-analysis-v1',#{model},#{documents},#{outcome},#{latency},#{prompt},#{completion},#{total},#{error},UTC_TIMESTAMP(3))"
  )
  int call(
    @Param("actor") long actor,
    @Param("order") long order,
    @Param("quote") long quote,
    @Param("hash") String hash,
    @Param("model") String model,
    @Param("documents") String documents,
    @Param("outcome") String outcome,
    @Param("latency") long latency,
    @Param("prompt") Integer prompt,
    @Param("completion") Integer completion,
    @Param("total") Integer total,
    @Param("error") String error
  );
}
