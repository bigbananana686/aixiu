package com.autocare.server.task;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface JobMapper {
  String COLUMNS =
    "id,type,business_key,actor_id,resource_id,payload,status,next_run_at,lease_owner,claim_token,lease_until,attempts,retry_generation,last_error_code,last_error_message,last_error_retryable,created_at,updated_at";

  @Select(
    "SELECT " + COLUMNS + " FROM t_background_job WHERE id=#{id} FOR UPDATE"
  )
  JobRow lock(long id);

  @Select("SELECT " + COLUMNS + " FROM t_background_job WHERE id=#{id}")
  JobRow find(long id);

  @Select("SELECT UTC_TIMESTAMP(3)")
  LocalDateTime now();

  @Insert(
    "INSERT INTO t_background_job(type,business_key,actor_id,resource_id,payload,next_run_at,created_at,updated_at) VALUES(#{type},#{businessKey},#{actorId},#{resourceId},#{payload},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(JobRow row);

  @Select(
    "SELECT " +
      COLUMNS +
      " FROM t_background_job WHERE type=#{type} AND attempts<3 AND ((status IN ('PENDING','RETRY_WAIT') AND next_run_at<=UTC_TIMESTAMP(3)) OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3))) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED"
  )
  JobRow candidate(String type);

  @Update(
    "UPDATE t_background_job SET status='FAILED',next_run_at=NULL,claim_token=NULL,lease_until=NULL,lease_owner=NULL,last_error_code='AI_UNAVAILABLE',last_error_message='任务租约到期，自动尝试次数已耗尽',last_error_retryable=1,updated_at=UTC_TIMESTAMP(3) WHERE type=#{type} AND status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3) AND attempts>=3"
  )
  int exhaust(String type);

  @Update(
    "UPDATE t_background_job SET status='RUNNING',claim_token=#{token},lease_owner=#{owner},lease_until=TIMESTAMPADD(MICROSECOND,#{micros},UTC_TIMESTAMP(3)),attempts=attempts+1,next_run_at=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}"
  )
  int claim(
    @Param("id") long id,
    @Param("token") String token,
    @Param("owner") String owner,
    @Param("micros") long micros
  );

  @Update(
    "UPDATE t_background_job SET lease_until=TIMESTAMPADD(MICROSECOND,#{micros},UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='RUNNING' AND claim_token=#{token} AND lease_until>UTC_TIMESTAMP(3)"
  )
  int renew(
    @Param("id") long id,
    @Param("token") String token,
    @Param("micros") long micros
  );

  @Update(
    "UPDATE t_background_job SET status='SUCCEEDED',lease_until=NULL,lease_owner=NULL,claim_token=NULL,next_run_at=NULL,last_error_code=NULL,last_error_message=NULL,last_error_retryable=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='RUNNING' AND claim_token=#{token} AND lease_until>UTC_TIMESTAMP(3)"
  )
  int succeed(@Param("id") long id, @Param("token") String token);

  @Update(
    "UPDATE t_background_job SET status=#{status},next_run_at=CASE WHEN #{status}='RETRY_WAIT' THEN TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(3)) ELSE NULL END,lease_owner=NULL,claim_token=NULL,lease_until=NULL,last_error_code=#{code},last_error_message=#{message},last_error_retryable=#{retryable},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='RUNNING' AND claim_token=#{token} AND lease_until>UTC_TIMESTAMP(3)"
  )
  int fail(
    @Param("id") long id,
    @Param("token") String token,
    @Param("status") String status,
    @Param("delay") int delay,
    @Param("code") String code,
    @Param("message") String message,
    @Param("retryable") boolean retryable
  );
}
