package com.autocare.server.knowledge;

import com.autocare.common.error.*;
import com.autocare.server.task.*;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** G3-01 发布底座：通过 JobService.complete 回调使用，始终 job→head。 */
@Service
public class KnowledgePublication {

  private final PublicationMapper mapper;
  private final ObjectMapper json;
  private final JobService jobs;

  public KnowledgePublication(
    PublicationMapper mapper,
    ObjectMapper json,
    JobService jobs
  ) {
    this.mapper = mapper;
    this.json = json;
    this.jobs = jobs;
  }

  public boolean publish(JobLease lease, int expectedChunks) {
    return jobs.complete(lease, job -> persist(job, expectedChunks));
  }

  public void persist(JobRow job, int expectedChunks) {
    if (
      !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
    ) throw new IllegalStateException(
      "Publication requires fenced job transaction"
    );
    if (
      !"KNOWLEDGE_INDEX".equals(job.type) || expectedChunks < 1
    ) throw ApiException.internal();
    var p = json.readTree(job.payload);
    String key = p.path("document_key").asString();
    int version = p.path("version").asInt();
    Integer desired = mapper.lockHead(key);
    var doc = mapper.document(key, version, job.id);
    if (
      desired == null ||
      doc == null ||
      doc.pageCount == null ||
      doc.charCount == null ||
      doc.chunks != expectedChunks ||
      doc.invalidChunks != 0
    ) throw ApiException.of(ErrorCode.INVALID_ARGUMENT, "知识版本尚未完整索引");
    boolean active = desired == version;
    mapper.mark(doc.id, active, active ? null : "SUPERSEDED");
    if (
      active && mapper.activate(key, version) != 1
    ) throw ApiException.internal();
  }

  public static class Document {

    public long id;
    public Integer pageCount, charCount;
    public int chunks, invalidChunks;
  }

  @Mapper
  public interface PublicationMapper {
    @Select(
      "SELECT desired_version FROM t_knowledge_head WHERE document_key=#{key} FOR UPDATE"
    )
    Integer lockHead(String key);

    @Select(
      "SELECT d.id,d.page_count,d.char_count,(SELECT COUNT(*) FROM t_document_chunk c WHERE c.document_id=d.id) chunks,(SELECT COUNT(*) FROM t_document_chunk c WHERE c.document_id=d.id AND c.version<>d.version) invalid_chunks FROM t_knowledge_document d WHERE d.document_key=#{key} AND d.version=#{version} AND d.latest_job_id=#{job}"
    )
    Document document(
      @Param("key") String key,
      @Param("version") int version,
      @Param("job") long job
    );

    @Update(
      "UPDATE t_knowledge_document SET published=#{published},stale_reason=#{reason} WHERE id=#{id}"
    )
    int mark(
      @Param("id") long id,
      @Param("published") boolean published,
      @Param("reason") String reason
    );

    @Update(
      "UPDATE t_knowledge_head SET active_version=#{version},updated_at=UTC_TIMESTAMP(3) WHERE document_key=#{key} AND desired_version=#{version}"
    )
    int activate(@Param("key") String key, @Param("version") int version);
  }
}
