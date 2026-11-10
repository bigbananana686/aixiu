package com.autocare.server.task;

import com.autocare.common.error.*;
import com.autocare.server.knowledge.KnowledgeProperties;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 持久容量门：同一短事务串行接收，限制不是“先count再并发insert”的软上限。 */
@Service
public class JobAdmission {

  private final AdmissionMapper mapper;
  private final KnowledgeProperties p;

  public JobAdmission(AdmissionMapper mapper, KnowledgeProperties p) {
    this.mapper = mapper;
    this.p = p;
  }

  public void reserve(long actor) {
    if (
      !TransactionSynchronizationManager.isActualTransactionActive()
    ) throw new IllegalStateException("Admission requires transaction");
    mapper.lock();
    if (
      mapper.pending(null) >= p.maxPending() ||
      mapper.pending(actor) >= p.maxActorPending()
    ) throw ApiException.of(ErrorCode.AI_QUEUE_FULL);
    if (mapper.recent(actor) >= p.perMinute()) throw ApiException.of(
      ErrorCode.RATE_LIMITED
    );
  }

  @Mapper
  public interface AdmissionMapper {
    @Select("SELECT id FROM t_ai_admission WHERE id=1 FOR UPDATE")
    int lock();

    @Select({
      "<script>SELECT COUNT(*) FROM t_background_job WHERE status IN ('PENDING','RUNNING','RETRY_WAIT') <if test='actor != null'>AND actor_id=#{actor}</if></script>",
    })
    long pending(@Param("actor") Long actor);

    @Select(
      "SELECT COUNT(*) FROM t_background_job WHERE actor_id=#{actor} AND created_at > TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(3))"
    )
    long recent(long actor);
  }
}
