package com.autocare.server.knowledge;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface KnowledgeMapper {
  String DOC =
    "d.id,d.document_key,d.version,d.title,d.model_tags,d.source,d.content_type,d.size_bytes,d.file_path,d.page_count,d.char_count,d.published,d.stale_reason,d.latest_job_id,d.created_at,d.index_path,d.index_space,d.embedding_model,d.embedding_dimensions,d.chunk_strategy,h.desired_version,h.active_version,j.status index_status";
  String JOIN =
    " FROM t_knowledge_document d JOIN t_knowledge_head h ON h.document_key=d.document_key LEFT JOIN t_background_job j ON j.id=d.latest_job_id ";

  @Select("SELECT " + DOC + JOIN + "WHERE d.latest_job_id=#{job}")
  KnowledgeRow byJob(long job);

  @Select(
    "SELECT desired_version FROM t_knowledge_head WHERE document_key=#{key}"
  )
  Integer desired(String key);

  @Select(
    "SELECT desired_version FROM t_knowledge_head WHERE document_key=#{key} FOR UPDATE"
  )
  Integer lockHead(String key);

  @Insert(
    "INSERT INTO t_knowledge_head(document_key,desired_version,created_at,updated_at) VALUES(#{key},1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"
  )
  int newHead(String key);

  @Update(
    "UPDATE t_knowledge_head SET desired_version=#{version},updated_at=UTC_TIMESTAMP(3) WHERE document_key=#{key} AND desired_version=#{previous}"
  )
  int advance(
    @Param("key") String key,
    @Param("previous") int previous,
    @Param("version") int version
  );

  @Insert(
    "INSERT INTO t_knowledge_document(document_key,version,title,model_tags,source,content_type,size_bytes,file_path,latest_job_id,created_at) VALUES(#{documentKey},#{version},#{title},#{modelTags},#{source},#{contentType},#{sizeBytes},#{filePath},#{latestJobId},UTC_TIMESTAMP(3))"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(KnowledgeRow row);

  @Select({
    "<script>SELECT " +
      DOC +
      JOIN +
      "WHERE 1=1 <if test='key != null'>AND d.document_key=#{key}</if> <if test='tag != null'>AND JSON_CONTAINS(d.model_tags,JSON_QUOTE(#{tag}))</if> ORDER BY d.document_key,d.version DESC,d.latest_job_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
  })
  List<KnowledgeRow> list(
    @Param("key") String key,
    @Param("tag") String tag,
    @Param("limit") int limit,
    @Param("offset") int offset
  );

  @Select({
    "<script>SELECT COUNT(*) FROM t_knowledge_document d WHERE 1=1 <if test='key != null'>AND d.document_key=#{key}</if> <if test='tag != null'>AND JSON_CONTAINS(d.model_tags,JSON_QUOTE(#{tag}))</if></script>",
  })
  long count(@Param("key") String key, @Param("tag") String tag);

  @Select(
    "SELECT " +
      DOC +
      JOIN +
      "WHERE d.version=h.active_version AND d.published=1 AND d.index_path IS NOT NULL AND (JSON_CONTAINS(d.model_tags,JSON_QUOTE(#{model})) OR JSON_CONTAINS(d.model_tags,'\"GENERIC\"')) ORDER BY d.document_key LIMIT 101"
  )
  List<KnowledgeRow> active(String model);

  @Update(
    "UPDATE t_knowledge_document SET page_count=#{pages},char_count=#{chars},parsed_page_count=#{pages},parsed_char_count=#{chars},index_path=#{path},index_space=#{space},embedding_model=#{model},embedding_dimensions=#{dimensions},chunk_strategy=#{strategy} WHERE id=#{id}"
  )
  int indexed(
    @Param("id") long id,
    @Param("pages") int pages,
    @Param("chars") int chars,
    @Param("path") String path,
    @Param("space") String space,
    @Param("model") String model,
    @Param("dimensions") int dimensions,
    @Param("strategy") String strategy
  );

  @Update(
    "UPDATE t_knowledge_document SET parsed_page_count=#{pages},parsed_char_count=#{chars} WHERE latest_job_id=#{job}"
  )
  int actual(
    @Param("job") long job,
    @Param("pages") int pages,
    @Param("chars") int chars
  );

  @Insert(
    "INSERT INTO t_document_chunk(document_id,version,chunk_key,source_locator,text_hash,text_content,created_at) VALUES(#{document},#{version},#{key},#{locator},#{hash},#{text},UTC_TIMESTAMP(3))"
  )
  int chunk(
    @Param("document") long document,
    @Param("version") int version,
    @Param("key") String key,
    @Param("locator") String locator,
    @Param("hash") String hash,
    @Param("text") String text
  );

  @Select(
    "SELECT active_version FROM t_knowledge_head WHERE document_key=#{key}"
  )
  Integer activeVersion(String key);

  @Select(
    "SELECT COUNT(*) FROM t_knowledge_document WHERE file_path=#{path} OR index_path=#{path}"
  )
  long references(String path);

  @Select(
    "SELECT COUNT(*) FROM t_background_job WHERE claim_token=#{token} AND status='RUNNING'"
  )
  long currentToken(String token);
}
