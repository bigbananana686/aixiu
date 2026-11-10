package com.autocare.server.knowledge;

import com.autocare.ai.knowledge.KnowledgeFiles;
import com.autocare.ai.knowledge.KnowledgeIndex;
import com.autocare.server.task.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只清理受控目录24小时以上、无数据库引用且无有效索引租约的孤儿文件。 */
@Component
public class KnowledgeFileMaintenance {

  private final KnowledgeFiles files;
  private final KnowledgeIndex index;
  private final KnowledgeMapper mapper;
  private final JobAdmission.AdmissionMapper admission;
  private final TransactionTemplate tx;
  private final TaskProperties tasks;

  public KnowledgeFileMaintenance(
    KnowledgeFiles files,
    KnowledgeIndex index,
    KnowledgeMapper mapper,
    JobAdmission.AdmissionMapper admission,
    PlatformTransactionManager manager,
    TaskProperties tasks
  ) {
    this.files = files;
    this.index = index;
    this.mapper = mapper;
    this.admission = admission;
    this.tx = new TransactionTemplate(manager);
    this.tasks = tasks;
  }

  @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
  public void scan() {
    if (tasks.enabled()) cleanOld();
  }

  public int cleanOld() {
    if (!Files.isDirectory(files.root())) return 0;
    var cutoff = Instant.now().minus(Duration.ofHours(24));
    List<Path> candidates;
    try (var list = Files.list(files.root())) {
      candidates = list
        .filter(p ->
          p
            .getFileName()
            .toString()
            .matches(
              "(?:raw-[a-f0-9]{64}\\.bin|(?:index|vector)-[a-f0-9-]{36}\\.json|upload-[A-Za-z0-9-]+\\.tmp)"
            )
        )
        .filter(p -> {
          try {
            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)
              .toInstant()
              .isBefore(cutoff);
          } catch (java.io.IOException e) {
            return false;
          }
        })
        .limit(100)
        .toList();
    } catch (java.io.IOException e) {
      return 0;
    }
    List<Path> orphanIndexes = new ArrayList<>();
    int removed = tx.execute(s -> {
      // 与持久接收串行复核引用；不进行解析、Embedding或网络调用。
      admission.lock();
      int deleted = 0;
      for (Path candidate : candidates) {
        Path p = files.resolveOwned(candidate.toString());
        String name = p.getFileName().toString();
        if (mapper.references(p.toString()) != 0) continue;
        String token = name.startsWith("vector-")
          ? name.substring(7, 43)
          : name.startsWith("index-")
            ? name.substring(6, 42)
            : null;
        if (token != null && mapper.currentToken(token) != 0) continue;
        // token释放与路径发布同事务；按此顺序再查引用，避免漏掉并发完成。
        if (
          token != null &&
          mapper.references(
            files
              .root()
              .resolve("index-" + token + ".json")
              .toString()
          ) != 0
        ) continue;
        if (name.startsWith("index-")) {
          orphanIndexes.add(p);
          continue;
        }
        try {
          if (
            Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)
              .toInstant()
              .isBefore(cutoff) &&
            Files.deleteIfExists(p)
          ) deleted++;
        } catch (java.io.IOException ignored) {
          /* 仍被使用或暂时不可删除，下轮再试 */
        }
      }
      return deleted;
    });
    for (Path p : orphanIndexes) {
      try {
        index.discardUnpublished(p);
        if (Files.deleteIfExists(p)) removed++;
      } catch (java.io.IOException | RuntimeException ignored) {
        /* 保留清单，下轮重试Redis回收 */
      }
    }
    return removed;
  }
}
