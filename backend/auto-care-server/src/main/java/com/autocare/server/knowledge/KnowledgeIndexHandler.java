package com.autocare.server.knowledge;

import com.autocare.ai.knowledge.*;
import com.autocare.common.error.*;
import com.autocare.server.task.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class KnowledgeIndexHandler implements JobHandler {

  private final KnowledgeMapper mapper;
  private final KnowledgeIndex index;
  private final KnowledgePublication publication;
  private final JobService jobs;
  private final KnowledgeCallMapper calls;
  private final EmbeddingPort embeddings;
  private final tools.jackson.databind.ObjectMapper json;

  public KnowledgeIndexHandler(
    KnowledgeMapper mapper,
    KnowledgeIndex index,
    KnowledgePublication publication,
    JobService jobs,
    KnowledgeCallMapper calls,
    EmbeddingPort embeddings,
    tools.jackson.databind.ObjectMapper json
  ) {
    this.mapper = mapper;
    this.index = index;
    this.publication = publication;
    this.jobs = jobs;
    this.calls = calls;
    this.embeddings = embeddings;
    this.json = json;
  }

  public String type() {
    return "KNOWLEDGE_INDEX";
  }

  public Consumer<JobRow> prepare(JobLease lease, JobRow job) {
    if (
      TransactionSynchronizationManager.isActualTransactionActive()
    ) throw new IllegalStateException("Indexing requires no transaction");
    KnowledgeRow doc = mapper.byJob(job.id);
    if (doc == null) throw ApiException.of(ErrorCode.JOB_NOT_RETRYABLE);
    long started = System.nanoTime();
    KnowledgeIndex.Prepared prepared;
    try {
      prepared = index.prepare(
        doc.filePath,
        doc.contentType,
        lease.claimToken()
      );
    } catch (DocumentParser.ParseLimitException e) {
      log(job, doc, started, "VALIDATION_FAILED", "INVALID_ARGUMENT");
      jobs.failWith(lease, ErrorCode.INVALID_ARGUMENT, false, r ->
        mapper.actual(r.id, e.pages, e.chars)
      );
      throw new JobService.StaleLeaseException();
    } catch (IllegalArgumentException | KnowledgeFiles.InvalidFileException e) {
      log(job, doc, started, "VALIDATION_FAILED", "INVALID_ARGUMENT");
      throw ApiException.of(ErrorCode.INVALID_ARGUMENT, "资料或索引输出不合法");
    } catch (java.io.IOException e) {
      log(job, doc, started, "FAILED", "RETRYABLE_STORAGE_ERROR");
      throw ApiException.of(ErrorCode.RETRYABLE_STORAGE_ERROR);
    } catch (RuntimeException e) {
      log(job, doc, started, "FAILED", "AI_UNAVAILABLE");
      throw ApiException.of(ErrorCode.AI_UNAVAILABLE);
    }
    log(job, doc, started, "SUCCEEDED", null);
    return current -> {
      if (
        !java.nio.file.Files.isRegularFile(
          java.nio.file.Path.of(prepared.path())
        )
      ) throw ApiException.of(ErrorCode.RETRYABLE_STORAGE_ERROR);
      for (var c : prepared.chunks())
        mapper.chunk(
          doc.id,
          doc.version,
          c.key(),
          c.locator(),
          c.hash(),
          c.text()
        );
      if (
        mapper.indexed(
          doc.id,
          prepared.pages(),
          prepared.chars(),
          prepared.path(),
          prepared.space(),
          prepared.model(),
          prepared.dimensions(),
          prepared.strategy()
        ) != 1
      ) throw ApiException.internal();
      publication.persist(current, prepared.chunks().size());
    };
  }

  private void log(
    JobRow job,
    KnowledgeRow doc,
    long started,
    String outcome,
    String error
  ) {
    // 原始文件内容hash由受控存储名取得；绝不把文件内容、路径或提示词写入调用表。
    String raw = java.nio.file.Path.of(doc.filePath).getFileName().toString();
    String hash = raw.matches("raw-[a-f0-9]{64}\\.bin")
      ? raw.substring(4, 68)
      : KnowledgeFiles.hash(doc.documentKey + ":" + doc.version);
    calls.insert(
      job.actorId,
      hash,
      KnowledgeIndex.STRATEGY,
      embeddings.modelId(),
      json.writeValueAsString(
        java.util.List.of(
          java.util.Map.of(
            "documentKey",
            doc.documentKey,
            "version",
            "" + doc.version
          )
        )
      ),
      outcome,
      Math.min(600000, (System.nanoTime() - started) / 1_000_000),
      error
    );
  }
}
