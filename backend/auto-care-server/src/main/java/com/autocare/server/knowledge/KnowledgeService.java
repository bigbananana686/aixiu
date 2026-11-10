package com.autocare.server.knowledge;

import com.autocare.ai.knowledge.*;
import com.autocare.common.error.*;
import com.autocare.server.command.*;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.task.*;
import com.autocare.server.web.UtcTimes;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeService {

  private final KnowledgeMapper mapper;
  private final KnowledgeFiles files;
  private final ObjectMapper json;
  private final JobService jobs;
  private final JobAdmission admission;
  private final AdminAccess admin;
  private final JobAuthorization.AccessMapper access;
  private final CommandExecutor commands;

  public KnowledgeService(
    KnowledgeMapper mapper,
    KnowledgeFiles files,
    ObjectMapper json,
    JobService jobs,
    JobAdmission admission,
    AdminAccess admin,
    JobAuthorization.AccessMapper access,
    CommandExecutor commands
  ) {
    this.mapper = mapper;
    this.files = files;
    this.json = json;
    this.jobs = jobs;
    this.admission = admission;
    this.admin = admin;
    this.access = access;
    this.commands = commands;
  }

  public CommandResult upload(
    long actor,
    MultipartFile file,
    MultiValueMap<String, String> form,
    String key,
    String requestId
  ) {
    admin.require(actor);
    KnowledgeInputs.query(
      form,
      Set.of("title", "documentKey", "modelTags", "source")
    );
    String title = KnowledgeInputs.text(
      form.getFirst("title"),
      "title",
      200,
      true
    );
    String documentKey = KnowledgeInputs.key(form.getFirst("documentKey"));
    String source = KnowledgeInputs.text(
      form.getFirst("source"),
      "source",
      200,
      false
    );
    List<String> tags = KnowledgeInputs.tags(form.getFirst("modelTags"));
    if (file == null) throw ApiException.invalid(FieldErrors.required("/file"));
    String name = KnowledgeInputs.text(
      file.getOriginalFilename(),
      "file",
      255,
      true
    );
    if (
      name.contains("/") ||
      name.contains("\\") ||
      name.contains("\0") ||
      name.equals(".") ||
      name.equals("..")
    ) throw ApiException.invalid(
      FieldErrors.invalidFormat("/file", "文件名不可含目录分隔符")
    );
    if (file.getSize() > KnowledgeFiles.MAX_BYTES) throw ApiException.of(
      ErrorCode.PAYLOAD_TOO_LARGE
    );
    // 幂等键先做形态校验；摘要中的文件字节hash在任何资源锁之外计算。
    IdempotencyKey.parse(key);
    KnowledgeFiles.Stored stored;
    try (var in = file.getInputStream()) {
      stored = files.store(in, file.getContentType());
    } catch (KnowledgeFiles.FileLimitException e) {
      throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE);
    } catch (KnowledgeFiles.InvalidFileException e) {
      throw ApiException.of(
        ErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "文件不是有效PDF或UTF-8文本"
      );
    } catch (java.io.IOException e) {
      throw ApiException.of(ErrorCode.RETRYABLE_STORAGE_ERROR);
    }
    Map<String, Object> digest = new LinkedHashMap<>();
    digest.put("documentKey", documentKey);
    digest.put("title", title);
    digest.put("source", source);
    digest.put("modelTags", tags);
    digest.put("fileHash", stored.hash());
    digest.put("sizeBytes", stored.bytes());
    digest.put("contentType", stored.contentType());
    return commands.execute(
      CommandSpec.of("uploadKnowledgeDocument"),
      key,
      digest,
      requestId,
      () -> {
        if (!"ADMIN".equals(access.role(actor))) throw ApiException.of(
          ErrorCode.FORBIDDEN
        );
        admission.reserve(actor);
        if (
          !java.nio.file.Files.isRegularFile(files.resolveOwned(stored.path()))
        ) throw ApiException.of(ErrorCode.RETRYABLE_STORAGE_ERROR);
        String docKey =
          documentKey == null ? "doc-" + UUID.randomUUID() : documentKey;
        Integer prior = mapper.desired(docKey);
        if (documentKey != null && prior == null) throw ApiException.of(
          ErrorCode.RESOURCE_NOT_FOUND
        );
        if (prior != null && prior == Integer.MAX_VALUE) throw ApiException.of(
          ErrorCode.RESOURCE_VERSION_CONFLICT
        );
        int version = prior == null ? 1 : prior + 1;
        JobRow job = new JobRow();
        job.actorId = actor;
        job.type = "KNOWLEDGE_INDEX";
        job.businessKey = "knowledge-index:" + docKey + ":" + version;
        job.payload = json.writeValueAsString(
          Map.of("document_key", docKey, "version", version)
        );
        long jobId = jobs.enqueue(job); // 先插入新job，再更新head；从不拿head去等一个已有job的锁。
        if (prior == null) mapper.newHead(docKey);
        else if (
          !Objects.equals(mapper.lockHead(docKey), prior) ||
          mapper.advance(docKey, prior, version) != 1
        ) throw ApiException.internal();
        KnowledgeRow row = new KnowledgeRow();
        row.documentKey = docKey;
        row.version = version;
        row.title = title;
        row.modelTags = json.writeValueAsString(tags);
        row.source = source;
        row.contentType = stored.contentType();
        row.sizeBytes = stored.bytes();
        row.filePath = stored.path();
        row.latestJobId = jobId;
        mapper.insert(row);
        var data = new LinkedHashMap<String, Object>();
        data.put("documentKey", docKey);
        data.put("version", "" + version);
        data.put("jobId", "" + jobId);
        data.put("indexStatus", "PENDING");
        data.put("fileName", name);
        data.put("contentType", stored.contentType());
        data.put("sizeBytes", stored.bytes());
        data.put("uploadedAt", UtcTimes.format(java.time.Instant.now()));
        return new CommandOutcome<>(202, "/api/v1/jobs/" + jobId, data, jobId);
      },
      (who, resource) -> {
        admin.require(who);
        return resource != null && mapper.byJob(resource) != null;
      }
    );
  }

  public record Page(
    List<Map<String, Object>> items,
    int page,
    int pageSize,
    long total
  ) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(long actor, MultiValueMap<String, String> query) {
    admin.require(actor);
    KnowledgeInputs.query(
      query,
      Set.of("page", "pageSize", "documentKey", "modelTag")
    );
    String key = KnowledgeInputs.key(query.getFirst("documentKey")),
      tag = KnowledgeInputs.tag(query.getFirst("modelTag"));
    int page = KnowledgeInputs.page(query.getFirst("page"), "page", 1, 10000),
      size = KnowledgeInputs.page(
        query.getFirst("pageSize"),
        "pageSize",
        20,
        100
      );
    return new Page(
      mapper
        .list(key, tag, size, (page - 1) * size)
        .stream()
        .map(this::view)
        .toList(),
      page,
      size,
      mapper.count(key, tag)
    );
  }

  private Map<String, Object> view(KnowledgeRow r) {
    var v = new LinkedHashMap<String, Object>();
    v.put("documentKey", r.documentKey);
    v.put("version", "" + r.version);
    v.put("title", r.title);
    v.put("modelTags", json.readTree(r.modelTags));
    v.put("source", r.source);
    v.put("contentType", r.contentType);
    v.put("sizeBytes", r.sizeBytes);
    v.put("desiredVersion", "" + r.desiredVersion);
    v.put(
      "activeVersion",
      r.activeVersion == null ? null : "" + r.activeVersion
    );
    v.put("indexStatus", r.indexStatus);
    v.put("jobId", r.latestJobId == null ? null : "" + r.latestJobId);
    boolean active = r.published && Objects.equals(r.activeVersion, r.version);
    v.put("published", active);
    v.put("staleReason", r.published && !active ? "SUPERSEDED" : r.staleReason);
    v.put("createdAt", UtcTimes.format(r.createdAt));
    return v;
  }
}
