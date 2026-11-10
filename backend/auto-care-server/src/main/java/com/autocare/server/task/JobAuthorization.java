package com.autocare.server.task;

import com.autocare.common.error.*;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 显式使用持久化 actor；执行前、写结果前及读取时均调用。 */
@Service
public class JobAuthorization {

  private final AccessMapper access;
  private final ObjectMapper json;

  public JobAuthorization(AccessMapper access, ObjectMapper json) {
    this.access = access;
    this.json = json;
  }

  public void require(JobRow job) {
    if (job == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    String role = access.role(job.actorId);
    if (role == null) throw ApiException.of(ErrorCode.JOB_NOT_RETRYABLE);
    var p = json.readTree(job.payload);
    boolean valid;
    if ("KNOWLEDGE_INDEX".equals(job.type)) {
      valid =
        "ADMIN".equals(role) &&
        p.path("document_key").isString() &&
        p.path("version").isIntegralNumber() &&
        access.document(
          job.id,
          p.path("document_key").asString(),
          p.path("version").asInt()
        ) != null;
    } else if ("QUOTE_ANALYSIS".equals(job.type)) {
      var q = access.quote(job.resourceId);
      // 报价内容不可变，先无锁取得其订单身份；只共享锁定订单归属。
      // 避免 join 的执行计划先锁 quote 再锁 order，与业务 order→quote 锁序相反。
      var order = q == null ? null : access.order(q.orderId);
      valid =
        q != null &&
        order != null &&
        (("OWNER".equals(role) && order.ownerId == job.actorId) ||
          ("TECH".equals(role) && order.technicianId == job.actorId)) &&
        p.path("quote_id").asText().equals(String.valueOf(job.resourceId)) &&
        p.path("quote_version").asInt() == q.version &&
        p.path("content_hash").asText().equals(q.contentHash);
    } else valid = false;
    if (!valid) throw ApiException.of(ErrorCode.JOB_NOT_RETRYABLE);
  }

  public static class QuoteAccess {

    public long orderId;
    public int version;
    public String contentHash;
  }

  public static class OrderAccess {

    public long ownerId, technicianId;
  }

  @Mapper
  public interface AccessMapper {
    @Select("SELECT role FROM t_user WHERE id=#{actor} AND enabled=1 FOR SHARE")
    String role(long actor);

    @Select(
      "SELECT order_id,version,content_hash FROM t_order_quote WHERE id=#{id}"
    )
    QuoteAccess quote(Long id);

    @Select(
      "SELECT owner_id,technician_id FROM t_service_order WHERE id=#{id} FOR SHARE"
    )
    OrderAccess order(long id);

    @Select(
      "SELECT id FROM t_knowledge_document WHERE latest_job_id=#{job} AND document_key=#{key} AND version=#{version} FOR SHARE"
    )
    Long document(
      @Param("job") long job,
      @Param("key") String key,
      @Param("version") int version
    );
  }
}
