package com.autocare.server.task;

import com.autocare.common.error.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobService {

  private final JobMapper mapper;
  private final JobAuthorization authorization;
  private final TaskProperties properties;
  private final TransactionTemplate tx;

  public JobService(
    JobMapper mapper,
    JobAuthorization authorization,
    TaskProperties properties,
    PlatformTransactionManager manager
  ) {
    this.mapper = mapper;
    this.authorization = authorization;
    this.properties = properties;
    this.tx = new TransactionTemplate(manager);
  }

  /** 与业务元信息在调用者事务中一起入库。业务键冲突由唯一约束拒绝。 */
  public long enqueue(JobRow row) {
    if (
      !Set.of("QUOTE_ANALYSIS", "KNOWLEDGE_INDEX").contains(row.type) ||
      row.businessKey == null ||
      row.businessKey.length() > 200 ||
      row.actorId == null ||
      row.payload == null ||
      row.payload.length() > 16000
    ) throw new IllegalArgumentException("Invalid job identity");
    return tx.execute(s -> {
      mapper.insert(row);
      return row.id;
    });
  }

  public JobLease claim(String type, String worker) {
    if (
      !Set.of("QUOTE_ANALYSIS", "KNOWLEDGE_INDEX").contains(type) ||
      worker == null ||
      !worker.matches("[A-Za-z0-9_.-]{1,64}")
    ) throw new IllegalArgumentException("Invalid worker/type");
    return tx.execute(s -> {
      mapper.exhaust(type);
      var row = mapper.candidate(type);
      if (row == null) return null;
      String token = UUID.randomUUID().toString();
      if (
        mapper.claim(
          row.id,
          token,
          worker,
          properties.lease().toNanos() / 1000
        ) != 1
      ) throw ApiException.internal();
      return new JobLease(row.id, token);
    });
  }

  public boolean renew(JobLease lease) {
    return tx.execute(s -> {
      try {
        valid(lease);
      } catch (StaleLeaseException e) {
        return false;
      }
      return (
        mapper.renew(
          lease.jobId(),
          lease.claimToken(),
          properties.lease().toNanos() / 1000
        ) == 1
      );
    });
  }

  public JobRow beforeExecution(JobLease lease) {
    return tx.execute(s -> {
      var row = valid(lease);
      authorization.require(row);
      return row;
    });
  }

  /** 回调只做数据库结果写入，不能在这里调用模型、文件解析或向量网络。 */
  public boolean complete(JobLease lease, Consumer<JobRow> saveResult) {
    return tx.execute(s -> {
      var row = valid(lease);
      authorization.require(row);
      saveResult.accept(row);
      // 回调可能等待 head/结果锁，所以完成语句再次用数据库当前时间检查租约。
      if (
        mapper.succeed(row.id, lease.claimToken()) != 1
      ) throw new StaleLeaseException();
      return true;
    });
  }

  public boolean fail(JobLease lease, ErrorCode code, boolean retryable) {
    return failWith(lease, code, retryable, row -> {});
  }

  /** 在同一fenced失败事务中保存解析实际计数；旧token不允许改写元信息。 */
  public boolean failWith(
    JobLease lease,
    ErrorCode code,
    boolean retryable,
    Consumer<JobRow> saveFailure
  ) {
    return tx.execute(s -> {
      JobRow row;
      try {
        row = valid(lease);
      } catch (StaleLeaseException e) {
        return false;
      }
      saveFailure.accept(row);
      boolean retry = retryable && row.attempts < 3;
      int changed = mapper.fail(
        row.id,
        lease.claimToken(),
        retry ? "RETRY_WAIT" : "FAILED",
        row.attempts == 1 ? 5 : 30,
        code.name(),
        code.defaultMessage(),
        retryable
      );
      if (changed != 1) throw new StaleLeaseException();
      return true;
    });
  }

  public JobRow read(long actor, long id) {
    return tx.execute(s -> {
      var row = mapper.find(id);
      if (row == null || row.actorId != actor) throw ApiException.of(
        ErrorCode.RESOURCE_NOT_FOUND
      );
      try {
        authorization.require(row);
      } catch (ApiException e) {
        throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
      }
      return row;
    });
  }

  private JobRow valid(JobLease lease) {
    var row = mapper.lock(lease.jobId());
    if (
      row == null ||
      !"RUNNING".equals(row.status) ||
      !Objects.equals(row.claimToken, lease.claimToken()) ||
      row.leaseUntil == null ||
      !row.leaseUntil.isAfter(mapper.now())
    ) throw new StaleLeaseException();
    return row;
  }

  public static class StaleLeaseException extends RuntimeException {}
}
