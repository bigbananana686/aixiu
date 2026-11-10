package com.autocare.server.task;

import static org.assertj.core.api.Assertions.*;

import com.autocare.common.error.*;
import com.autocare.server.knowledge.KnowledgePublication;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = { "autocare.tasks.enabled=false" })
class JobFoundationTest {

  @Autowired
  JobService jobs;

  @Autowired
  JobMapper mapper;

  @Autowired
  KnowledgePublication publication;

  @Autowired
  JdbcTemplate db;

  @Autowired
  PlatformTransactionManager manager;

  List<Long> ids = new ArrayList<>();
  List<String> docs = new ArrayList<>();

  @BeforeEach
  void guard() {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class))
      .matches("auto_care_g104_[a-z0-9_]+")
      .isEqualTo(System.getenv("DB_NAME"));
  }

  @AfterEach
  void clean() {
    db.execute("DROP TRIGGER IF EXISTS d4_fail_completion");
    db.update("UPDATE t_user SET enabled=1 WHERE id=5");
    for (String key : docs) {
      db.update(
        "DELETE c FROM t_document_chunk c JOIN t_knowledge_document d ON d.id=c.document_id WHERE d.document_key=?",
        key
      );
      db.update("DELETE FROM t_knowledge_document WHERE document_key=?", key);
      db.update("DELETE FROM t_knowledge_head WHERE document_key=?", key);
    }
    for (long id : ids)
      db.update("DELETE FROM t_background_job WHERE id=?", id);
  }

  JobRow row(String key, int version) {
    JobRow r = new JobRow();
    r.actorId = 5L;
    r.type = "KNOWLEDGE_INDEX";
    r.businessKey = "d4:" + key + ":" + version;
    r.payload =
      "{\"document_key\":\"" + key + "\",\"version\":" + version + "}";
    return r;
  }

  long fixture(String key, int version) {
    if (!docs.contains(key)) {
      docs.add(key);
      db.update(
        "INSERT INTO t_knowledge_head(document_key,desired_version,created_at,updated_at) VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        key,
        version
      );
    } else db.update(
      "UPDATE t_knowledge_head SET desired_version=? WHERE document_key=?",
      version,
      key
    );
    JobRow r = row(key, version);
    long id = jobs.enqueue(r);
    ids.add(id);
    db.update(
      "INSERT INTO t_knowledge_document(document_key,version,title,model_tags,content_type,size_bytes,file_path,page_count,char_count,latest_job_id,created_at) VALUES(?,?,'synthetic','[\"COROLLA_2020\"]','text/plain',10,'d4-test-only',1,10,?,UTC_TIMESTAMP(3))",
      key,
      version,
      id
    );
    long d = db.queryForObject(
      "SELECT id FROM t_knowledge_document WHERE document_key=? AND version=?",
      Long.class,
      key,
      version
    );
    db.update(
      "INSERT INTO t_document_chunk(document_id,version,chunk_key,source_locator,text_hash,text_content,created_at) VALUES(?,?,'c1','section1',REPEAT('a',64),'synthetic',UTC_TIMESTAMP(3))",
      d,
      version
    );
    return id;
  }

  JobLease claim() {
    return jobs.claim("KNOWLEDGE_INDEX", "same-worker");
  }

  void expire(long id) {
    db.update(
      "UPDATE t_background_job SET lease_until=UTC_TIMESTAMP(3) WHERE id=?",
      id
    );
  }

  @Test
  void sameWorkerReclaim_invalidatesOldRenewCompleteAndPublication() {
    long id = fixture("d4-fence", 1);
    JobLease old = claim();
    expire(id);
    JobLease current = claim();
    assertThat(current.jobId()).isEqualTo(id);
    assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
    assertThat(jobs.renew(old)).isFalse();
    assertThatThrownBy(() -> jobs.complete(old, r -> {})).isInstanceOf(
      JobService.StaleLeaseException.class
    );
    assertThatThrownBy(() -> publication.publish(old, 1)).isInstanceOf(
      JobService.StaleLeaseException.class
    );
    assertThat(publication.publish(current, 1)).isTrue();
    assertThat(
      db.queryForObject(
        "SELECT active_version FROM t_knowledge_head WHERE document_key='d4-fence'",
        Integer.class
      )
    ).isEqualTo(1);
  }

  @Test
  void exactLeaseBoundary_isExpiredWithoutReclaim() {
    long id = fixture("d4-equal", 1);
    var lease = claim();
    expire(id);
    assertThat(jobs.renew(lease)).isFalse();
    assertThat(jobs.fail(lease, ErrorCode.AI_UNAVAILABLE, true)).isFalse();
    assertThatThrownBy(() -> jobs.complete(lease, r -> {})).isInstanceOf(
      JobService.StaleLeaseException.class
    );
  }

  @Test
  void twoClaimers_onlyOneOwnsTask() throws Exception {
    fixture("d4-race", 1);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<JobLease>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) futures.add(
        pool.submit(() -> {
          start.await();
          return claim();
        })
      );
      start.countDown();
      List<JobLease> claimed = new ArrayList<>();
      for (var f : futures) {
        var l = f.get(10, TimeUnit.SECONDS);
        if (l != null) claimed.add(l);
      }
      assertThat(claimed).hasSize(1);
      assertThat(mapper.find(claimed.getFirst().jobId()).attempts).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void retryBackoff5Then30_thenFailedAfterThirdAttempt() {
    long id = fixture("d4-retry", 1);
    for (int attempt = 1; attempt <= 3; attempt++) {
      var lease = claim();
      assertThat(jobs.fail(lease, ErrorCode.AI_UNAVAILABLE, true)).isTrue();
      var r = mapper.find(id);
      assertThat(r.attempts).isEqualTo(attempt);
      assertThat(r.status).isEqualTo(attempt < 3 ? "RETRY_WAIT" : "FAILED");
      if (attempt < 3) {
        long delta = db.queryForObject(
          "SELECT TIMESTAMPDIFF(MICROSECOND,updated_at,next_run_at)/1000 FROM t_background_job WHERE id=?",
          Long.class,
          id
        );
        assertThat(delta).isEqualTo(attempt == 1 ? 5000 : 30000);
        assertThat(claim()).isNull();
        db.update(
          "UPDATE t_background_job SET next_run_at=UTC_TIMESTAMP(3) WHERE id=?",
          id
        );
      }
    }
    assertThat(claim()).isNull();
  }

  @Test
  void crashedThirdAttempt_isExhaustedRatherThanRunningForever() {
    long id = fixture("d4-crash-limit", 1);
    for (int i = 0; i < 3; i++) {
      assertThat(claim()).isNotNull();
      expire(id);
    }
    assertThat(claim()).isNull();
    assertThat(mapper.find(id).status).isEqualTo("FAILED");
  }

  @Test
  void invalidOutput_isNotRetried() {
    long id = fixture("d4-invalid", 1);
    jobs.fail(claim(), ErrorCode.INVALID_ARGUMENT, false);
    assertThat(mapper.find(id).status).isEqualTo("FAILED");
    assertThat(mapper.find(id).attempts).isEqualTo(1);
  }

  @Test
  void explicitActor_doesNotNeedSecurityThreadLocal_andReadIsOwned() {
    long id = fixture("d4-identity", 1);
    var lease = claim();
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    assertThat(jobs.beforeExecution(lease).actorId).isEqualTo(5);
    assertThat(jobs.read(5, id).id).isEqualTo(id);
    assertThatThrownBy(() -> jobs.read(1, id))
      .isInstanceOf(ApiException.class)
      .satisfies(e ->
        assertThat(((ApiException) e).errorCode()).isEqualTo(
          ErrorCode.RESOURCE_NOT_FOUND
        )
      );
  }

  @Test
  void disabledBeforeExecution_cannotRun() {
    fixture("d4-disabled-before", 1);
    var lease = claim();
    db.update("UPDATE t_user SET enabled=0 WHERE id=5");
    assertThatThrownBy(() -> jobs.beforeExecution(lease)).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void disabledDuringWork_cannotPublishOrRead() {
    long id = fixture("d4-disabled-after", 1);
    var lease = claim();
    jobs.beforeExecution(lease);
    db.update("UPDATE t_user SET enabled=0 WHERE id=5");
    assertThatThrownBy(() -> publication.publish(lease, 1)).isInstanceOf(
      ApiException.class
    );
    assertThatThrownBy(() -> jobs.read(5, id)).isInstanceOf(ApiException.class);
    assertThat(
      db.queryForObject(
        "SELECT active_version FROM t_knowledge_head WHERE document_key='d4-disabled-after'",
        Integer.class
      )
    ).isNull();
  }

  @Test
  void oldVersionCompletesAsHistory_neverDowngradesActive() {
    long first = fixture("d4-versions", 1);
    var old = claim();
    long second = fixture("d4-versions", 2);
    var current = claim();
    assertThat(current.jobId()).isEqualTo(second);
    publication.publish(current, 1);
    publication.publish(old, 1);
    assertThat(mapper.find(first).status).isEqualTo("SUCCEEDED");
    assertThat(
      db.queryForObject(
        "SELECT active_version FROM t_knowledge_head WHERE document_key='d4-versions'",
        Integer.class
      )
    ).isEqualTo(2);
    assertThat(
      db.queryForObject(
        "SELECT stale_reason FROM t_knowledge_document WHERE document_key='d4-versions' AND version=1",
        String.class
      )
    ).isEqualTo("SUPERSEDED");
  }

  @Test
  void newVersionFailure_keepsOldActiveServing() {
    fixture("d4-new-failure", 1);
    publication.publish(claim(), 1);
    fixture("d4-new-failure", 2);
    jobs.fail(claim(), ErrorCode.INVALID_ARGUMENT, false);
    assertThat(
      db.queryForObject(
        "SELECT active_version FROM t_knowledge_head WHERE document_key='d4-new-failure'",
        Integer.class
      )
    ).isEqualTo(1);
  }

  @Test
  void incompleteChunks_areNotPublished() {
    fixture("d4-incomplete", 1);
    var lease = claim();
    assertThatThrownBy(() -> publication.publish(lease, 2)).isInstanceOf(
      ApiException.class
    );
    assertThat(
      db.queryForObject(
        "SELECT active_version FROM t_knowledge_head WHERE document_key='d4-incomplete'",
        Integer.class
      )
    ).isNull();
  }

  @Test
  void completionStorageFailure_rollsBackPublicationToo() {
    fixture("d4-rollback", 1);
    var lease = claim();
    var rootSource =
      new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:mysql://127.0.0.1:13306/" +
          System.getenv("DB_NAME") +
          "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
        "root",
        System.getenv("DB_ROOT_PASSWORD")
      );
    new JdbcTemplate(rootSource).execute(
      "CREATE TRIGGER d4_fail_completion BEFORE UPDATE ON t_background_job FOR EACH ROW BEGIN IF NEW.status='SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END"
    );
    assertThatThrownBy(() -> publication.publish(lease, 1)).isInstanceOf(
      RuntimeException.class
    );
    assertThat(mapper.find(lease.jobId()).status).isEqualTo("RUNNING");
    assertThat(
      db.queryForObject(
        "SELECT active_version FROM t_knowledge_head WHERE document_key='d4-rollback'",
        Integer.class
      )
    ).isNull();
    assertThat(
      db.queryForObject(
        "SELECT published FROM t_knowledge_document WHERE document_key='d4-rollback'",
        Integer.class
      )
    ).isZero();
  }

  @Test
  void delayedRenewal_cannotResurrectLeaseAfterWaitingForRowLock()
    throws Exception {
    long id = fixture("d4-renew-lock", 1);
    var lease = claim();
    var pool = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    try {
      new TransactionTemplate(manager).execute(s -> {
        db.queryForObject(
          "SELECT id FROM t_background_job WHERE id=? FOR UPDATE",
          Long.class,
          id
        );
        db.update(
          "UPDATE t_background_job SET lease_until=TIMESTAMPADD(MICROSECOND,200000,UTC_TIMESTAMP(3)) WHERE id=?",
          id
        );
        var f = pool.submit(() -> {
          entered.countDown();
          return jobs.renew(lease);
        });
        try {
          entered.await();
          Thread.sleep(350);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        result = f;
        return null;
      });
      assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
    } finally {
      pool.shutdownNow();
    }
  }

  Future<Boolean> result;

  @Test
  void boundedDispatcher_leavesUnclaimedWorkInDatabase_andResumesAfterCapacity()
    throws Exception {
    long a = fixture("d4-capacity-a", 1),
      b = fixture("d4-capacity-b", 1);
    CountDownLatch entered = new CountDownLatch(1),
      release = new CountDownLatch(1);
    JobHandler handler = new JobHandler() {
      public String type() {
        return "KNOWLEDGE_INDEX";
      }

      public java.util.function.Consumer<JobRow> prepare(JobLease l, JobRow r)
        throws Exception {
        entered.countDown();
        release.await();
        return row -> {};
      }
    };
    var props = new TaskProperties(
      true,
      1,
      0,
      java.time.Duration.ofSeconds(10),
      java.time.Duration.ofSeconds(1),
      "capacity-worker"
    );
    try (
      varCloser close = new varCloser(
        new JobDispatcher(jobs, props, List.of(handler))
      )
    ) {
      var dispatcher = close.value;
      dispatcher.scan();
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      dispatcher.scan();
      assertThat(mapper.find(a).status).isEqualTo("RUNNING");
      assertThat(mapper.find(b).status).isEqualTo("PENDING");
      assertThat(dispatcher.availableCapacity()).isZero();
      release.countDown();
      await(() -> mapper.find(a).status.equals("SUCCEEDED"));
      dispatcher.scan();
      await(() -> mapper.find(b).status.equals("SUCCEEDED"));
    } finally {
      release.countDown();
    }
  }

  @Test
  void executorSubmissionRejected_movesClaimToRetryWait() {
    long id = fixture("d4-reject", 1);
    JobHandler h = new JobHandler() {
      public String type() {
        return "KNOWLEDGE_INDEX";
      }

      public java.util.function.Consumer<JobRow> prepare(JobLease l, JobRow r) {
        return v -> {};
      }
    };
    var dispatcher = new JobDispatcher(
      jobs,
      new TaskProperties(
        true,
        1,
        0,
        java.time.Duration.ofSeconds(10),
        java.time.Duration.ofSeconds(1),
        "reject-worker"
      ),
      List.of(h)
    );
    dispatcher.close();
    dispatcher.scan();
    assertThat(mapper.find(id).status).isEqualTo("RETRY_WAIT");
    assertThat(mapper.find(id).claimToken).isNull();
  }

  @Test
  void applicationConnection_usesRcUtcInnoDb() {
    new TransactionTemplate(manager).execute(s -> {
      var r = db.queryForMap(
        "SELECT @@session.transaction_isolation isolation,@@session.time_zone zone,@@session.innodb_lock_wait_timeout wait_seconds"
      );
      System.out.println("D4 SESSION " + r);
      assertThat(r.get("isolation")).isEqualTo("READ-COMMITTED");
      assertThat(r.get("zone")).isIn("+00:00", "UTC");
      assertThat(((Number) r.get("wait_seconds")).intValue()).isEqualTo(5);
      return null;
    });
    assertThat(
      db.queryForObject(
        "SELECT ENGINE FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='t_background_job'",
        String.class
      )
    ).isEqualTo("InnoDB");
  }

  @Test
  void businessKeyUniqueness_andEnclosingRollback() {
    JobRow r = row("d4-atomic", 1);
    long id = jobs.enqueue(r);
    ids.add(id);
    assertThatThrownBy(() -> jobs.enqueue(row("d4-atomic", 1))).isInstanceOf(
      org.springframework.dao.DuplicateKeyException.class
    );
    JobRow rollback = row("d4-rolled-back", 1);
    new TransactionTemplate(manager).execute(s -> {
      jobs.enqueue(rollback);
      s.setRollbackOnly();
      return null;
    });
    assertThat(mapper.find(rollback.id)).isNull();
  }

  static void await(java.util.function.BooleanSupplier condition)
    throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (!condition.getAsBoolean() && System.nanoTime() < end)
      Thread.sleep(20);
    assertThat(condition.getAsBoolean()).isTrue();
  }

  static class varCloser implements AutoCloseable {

    final JobDispatcher value;

    varCloser(JobDispatcher v) {
      value = v;
    }

    public void close() {
      value.close();
    }
  }
}
