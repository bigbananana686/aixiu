package com.autocare.server.task;

import com.autocare.common.error.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

/** 无处理器时不领取；先占本地容量再领取，提交拒绝时释放租约进入退避。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(TaskProperties.class)
public class JobDispatcher {

  private final JobService jobs;
  private final TaskProperties properties;
  private final List<JobHandler> handlers;
  private final Semaphore slots;
  private final ThreadPoolExecutor executor;
  private final ScheduledExecutorService heartbeats;
  private final AtomicBoolean scanning = new AtomicBoolean();

  public JobDispatcher(
    JobService jobs,
    TaskProperties properties,
    List<JobHandler> handlers
  ) {
    this.jobs = jobs;
    this.properties = properties;
    this.handlers = List.copyOf(handlers);
    if (
      handlers.stream().map(JobHandler::type).distinct().count() !=
      handlers.size()
    ) throw new IllegalArgumentException("Duplicate task handler");
    slots = new Semaphore(properties.workers() + properties.queueCapacity());
    BlockingQueue<Runnable> queue =
      properties.queueCapacity() == 0
        ? new SynchronousQueue<>()
        : new ArrayBlockingQueue<>(properties.queueCapacity());
    executor = new ThreadPoolExecutor(
      properties.workers(),
      properties.workers(),
      0,
      TimeUnit.MILLISECONDS,
      queue,
      Thread.ofPlatform().name("autocare-jobs-", 0).factory(),
      new ThreadPoolExecutor.AbortPolicy()
    );
    heartbeats = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("autocare-lease-", 0).factory()
    );
  }

  @Scheduled(fixedDelayString = "${autocare.tasks.scan-delay:1000}")
  public void scan() {
    if (!properties.enabled() || !scanning.compareAndSet(false, true)) return;
    try {
      for (JobHandler handler : handlers) {
        // 单轮最多当前容量；不在请求线程执行，也不把数据库任务堆入无限内存队列。
        for (
          int i = 0;
          i < properties.workers() + properties.queueCapacity();
          i++
        ) {
          if (!slots.tryAcquire()) return;
          JobLease lease;
          try {
            lease = jobs.claim(handler.type(), properties.workerName());
          } catch (RuntimeException e) {
            slots.release();
            throw e;
          }
          if (lease == null) {
            slots.release();
            break;
          }
          try {
            executor.execute(() -> run(handler, lease));
          } catch (RejectedExecutionException e) {
            slots.release();
            jobs.fail(lease, ErrorCode.AI_QUEUE_FULL, true);
            return;
          }
        }
      }
    } finally {
      scanning.set(false);
    }
  }

  private void run(JobHandler handler, JobLease lease) {
    ScheduledFuture<?> heartbeat = null;
    try {
      var job = jobs.beforeExecution(lease);
      heartbeat = heartbeats.scheduleWithFixedDelay(
        () -> {
          try {
            jobs.renew(lease);
          } catch (RuntimeException ignored) {
            /* 到期后发布仍会被 fencing 拒绝 */
          }
        },
        properties.heartbeat().toMillis(),
        properties.heartbeat().toMillis(),
        TimeUnit.MILLISECONDS
      );
      var result = handler.prepare(lease, job);
      jobs.complete(lease, result);
    } catch (JobService.StaleLeaseException ignored) {
      // 新领取者负责，旧工作者既不能完成，也不能改写新领取者的失败状态。
    } catch (ApiException e) {
      jobs.fail(lease, e.errorCode(), e.errorCode().retryable());
    } catch (Exception e) {
      jobs.fail(lease, ErrorCode.INTERNAL_ERROR, false);
    } finally {
      if (heartbeat != null) heartbeat.cancel(false);
      slots.release();
    }
  }

  public int availableCapacity() {
    return slots.availablePermits();
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
    heartbeats.shutdownNow();
  }
}
