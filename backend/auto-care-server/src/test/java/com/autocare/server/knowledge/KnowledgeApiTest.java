package com.autocare.server.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.knowledge.*;
import com.autocare.common.error.*;
import com.autocare.server.security.JwtTokenIssuer;
import com.autocare.server.task.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "autocare.tasks.enabled=false",
    "autocare.knowledge.per-minute=100",
  }
)
@Import(D5TestConfiguration.class)
class KnowledgeApiTest {

  @Autowired
  JdbcTemplate db;

  @Autowired
  ObjectMapper json;

  @Autowired
  JwtTokenIssuer issuer;

  @Autowired
  JobService jobs;

  @Autowired
  JobMapper jobMapper;

  @Autowired
  KnowledgeIndexHandler handler;

  @Autowired
  KnowledgeMapper mapper;

  @Autowired
  D5TestConfiguration.FakeEmbeddings embeddings;

  @Autowired
  PlatformTransactionManager manager;

  @Autowired
  JobAdmission.AdmissionMapper admissionMapper;

  @Autowired
  KnowledgeProperties properties;

  @Autowired
  KnowledgeFileMaintenance maintenance;

  @Autowired
  KnowledgeFiles files;

  @LocalServerPort
  int port;

  List<String> keys = new ArrayList<>();
  List<Long> owned = new ArrayList<>();
  D5Http http;
  byte[] bytes =
    "合成资料：机油机滤更换须按车型手册核对，不构成真实维修建议。".getBytes(
      StandardCharsets.UTF_8
    );

  @BeforeEach
  void setup() {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class))
      .matches("auto_care_g104_[a-z0-9_]+")
      .isEqualTo(System.getenv("DB_NAME"));
    http = new D5Http(port, issuer, json);
    embeddings.unavailable = false;
  }

  @AfterEach
  void cleanup() {
    db.execute("DROP TRIGGER IF EXISTS d5_fail_complete");
    embeddings.unavailable = false;
    db.update("UPDATE t_user SET enabled=1 WHERE id=5");
    for (long id : owned) {
      var docs = db.queryForList(
        "SELECT document_key FROM t_knowledge_document WHERE latest_job_id=?",
        String.class,
        id
      );
      db.update(
        "DELETE c FROM t_document_chunk c JOIN t_knowledge_document d ON d.id=c.document_id WHERE d.latest_job_id=?",
        id
      );
      db.update("DELETE FROM t_knowledge_document WHERE latest_job_id=?", id);
      for (String key : docs)
        if (
          db.queryForObject(
            "SELECT COUNT(*) FROM t_knowledge_document WHERE document_key=?",
            Integer.class,
            key
          ) == 0
        ) db.update("DELETE FROM t_knowledge_head WHERE document_key=?", key);
      db.update("DELETE FROM t_background_job WHERE id=?", id);
    }
    db.update("DELETE FROM t_ai_call_log WHERE scene='KNOWLEDGE_INDEX'");
    for (String k : keys)
      db.update("DELETE FROM t_idempotency WHERE idempotency_key=?", k);
  }

  String key() {
    String k = UUID.randomUUID().toString();
    keys.add(k);
    return k;
  }

  void expect(D5Http.Reply r, int status, String code) {
    assertThat(r.http().statusCode()).isEqualTo(status);
    if (code != null) assertThat(
      r.body().path("error").path("code").asString()
    ).isEqualTo(code);
  }

  D5Http.Reply upload(String series, String key) throws Exception {
    var form = new LinkedHashMap<String, String>();
    form.put("title", "合成机油手册");
    form.put("modelTags", "GENERIC");
    if (series != null) form.put("documentKey", series);
    var r = http.upload(5, bytes, "manual.md", "text/markdown", form, key);
    if (r.data().has("jobId")) {
      long id = r.data().path("jobId").asLong();
      if (!owned.contains(id)) owned.add(id);
    }
    return r;
  }

  JobLease claim(long id) {
    var l = jobs.claim("KNOWLEDGE_INDEX", "d5-worker");
    assertThat(l.jobId()).isEqualTo(id);
    return l;
  }

  void complete(long id) {
    var l = claim(id);
    var save = handler.prepare(l, jobs.beforeExecution(l));
    jobs.complete(l, save);
  }

  @Test
  void uploadIsDurable202AndSuccessfulReplayDoesNotAddJob() throws Exception {
    String k = key();
    var a = upload(null, k);
    expect(a, 202, null);
    long id = a.data().path("jobId").asLong();
    assertThat(a.data().propertyNames()).containsExactlyInAnyOrder(
      "documentKey",
      "version",
      "jobId",
      "indexStatus",
      "fileName",
      "contentType",
      "sizeBytes",
      "uploadedAt"
    );
    assertThat(a.http().headers().firstValue("Location")).contains(
      "/api/v1/jobs/" + id
    );
    assertThat(jobMapper.find(id).status).isEqualTo("PENDING");
    assertThat(mapper.byJob(id).pageCount).isNull();
    var b = upload(null, k);
    expect(b, 202, null);
    assertThat(b.data()).isEqualTo(a.data());
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_background_job WHERE id=?",
        Integer.class,
        id
      )
    ).isEqualTo(1);
    var conflict = http.upload(
      5,
      "different".getBytes(),
      "manual.md",
      "text/markdown",
      Map.of("title", "合成机油手册", "modelTags", "GENERIC"),
      k
    );
    expect(conflict, 409, "IDEMPOTENCY_KEY_REUSED");
  }

  @ParameterizedTest
  @ValueSource(longs = { 0, 1, 3 })
  void uploadRequiresAdministrator(long actor) throws Exception {
    expect(
      http.upload(
        actor,
        bytes,
        "m.txt",
        "text/plain",
        Map.of("title", "x"),
        key()
      ),
      actor == 0 ? 401 : 403,
      actor == 0 ? "UNAUTHENTICATED" : "FORBIDDEN"
    );
  }

  @Test
  void inputMediaAndUnknownFieldsAreRejected() throws Exception {
    expect(
      http.upload(
        5,
        bytes,
        "../manual.txt",
        "text/plain",
        Map.of("title", "x"),
        key()
      ),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      http.upload(
        5,
        bytes,
        "m.pdf",
        "application/pdf",
        Map.of("title", "x"),
        key()
      ),
      415,
      "UNSUPPORTED_MEDIA_TYPE"
    );
    expect(
      http.upload(
        5,
        bytes,
        "m.txt",
        "text/plain",
        Map.of("title", "x", "url", "https://example.invalid"),
        key()
      ),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      http.upload(5, bytes, "m.txt", "text/plain", Map.of("title", " "), key()),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      http.upload(
        5,
        bytes,
        "m.txt",
        "text/plain",
        Map.of("title", "x", "modelTags", "bad tag"),
        key()
      ),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      http.upload(5, null, "m.txt", "text/plain", Map.of("title", "x"), key()),
      400,
      "INVALID_ARGUMENT"
    );
    expect(
      http.json("POST", "/admin/knowledge-documents", 5, "{}", key()),
      415,
      "UNSUPPORTED_MEDIA_TYPE"
    );
  }

  @Test
  void uploadOver64KiBIsAcceptedButOver10MiBIs413() throws Exception {
    byte[] medium = new byte[100_000];
    Arrays.fill(medium, (byte) 'a');
    var r = http.upload(
      5,
      medium,
      "m.txt",
      "text/plain",
      Map.of("title", "large"),
      key()
    );
    expect(r, 202, null);
    owned.add(r.data().path("jobId").asLong());
    byte[] large = new byte[10 * 1024 * 1024 + 1];
    Arrays.fill(large, (byte) 'a');
    expect(
      http.upload(
        5,
        large,
        "m.txt",
        "text/plain",
        Map.of("title", "large"),
        key()
      ),
      413,
      "PAYLOAD_TOO_LARGE"
    );
  }

  @Test
  void halfBuiltIsInvisibleAndCompletePublishesMetadataAndStableChunks()
    throws Exception {
    var r = upload(null, key());
    long id = r.data().path("jobId").asLong();
    assertThat(mapper.active("ANY_MODEL")).isEmpty();
    complete(id);
    var d = mapper.byJob(id);
    assertThat(d.published).isTrue();
    assertThat(d.activeVersion).isEqualTo(1);
    assertThat(d.indexSpace).hasSize(64);
    assertThat(d.embeddingModel).isEqualTo("controlled-embedding-v1");
    assertThat(d.embeddingDimensions).isEqualTo(2);
    assertThat(mapper.active("ANY_MODEL")).hasSize(1);
    var list = http.json(
      "GET",
      "/admin/knowledge-documents?documentKey=" + d.documentKey,
      5,
      null,
      null
    );
    expect(list, 200, null);
    assertThat(list.data().path("items").get(0).propertyNames()).doesNotContain(
      "filePath",
      "indexPath",
      "textContent",
      "payload"
    );
    assertThat(
      http
        .json("GET", "/jobs/" + id, 5, null, null)
        .data()
        .path("result")
        .isNull()
    ).isTrue();
  }

  @Test
  void oldCompletedTaskNeverRollsBackDesiredVersion() throws Exception {
    var a = upload(null, key());
    String series = a.data().path("documentKey").asString();
    long id1 = a.data().path("jobId").asLong();
    var old = claim(id1);
    var saveOld = handler.prepare(old, jobs.beforeExecution(old));
    var b = upload(series, key());
    long id2 = b.data().path("jobId").asLong();
    complete(id2);
    jobs.complete(old, saveOld);
    assertThat(mapper.byJob(id1).staleReason).isEqualTo("SUPERSEDED");
    assertThat(mapper.byJob(id1).published).isFalse();
    assertThat(mapper.byJob(id2).activeVersion).isEqualTo(2);
  }

  @Test
  void failedNewVersionKeepsOldActiveAndAttemptsAreRetryable()
    throws Exception {
    var a = upload(null, key());
    long first = a.data().path("jobId").asLong();
    complete(first);
    var b = upload(a.data().path("documentKey").asString(), key());
    long second = b.data().path("jobId").asLong();
    var lease = claim(second);
    embeddings.unavailable = true;
    assertThatThrownBy(() ->
      handler.prepare(lease, jobs.beforeExecution(lease))
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE)
    );
    jobs.fail(lease, ErrorCode.AI_UNAVAILABLE, true);
    assertThat(jobMapper.find(second).status).isEqualTo("RETRY_WAIT");
    assertThat(mapper.byJob(first).activeVersion).isEqualTo(1);
    assertThat(mapper.active("ANY_MODEL"))
      .extracting(d -> d.version)
      .containsExactly(1);
  }

  @Test
  void staleTokenCannotPersistPreparedChunksOrIndexPointer() throws Exception {
    long id = upload(null, key()).data().path("jobId").asLong();
    var old = claim(id);
    var prepared = handler.prepare(old, jobs.beforeExecution(old));
    db.update(
      "UPDATE t_background_job SET lease_until=UTC_TIMESTAMP(3) WHERE id=?",
      id
    );
    var current = claim(id);
    assertThatThrownBy(() -> jobs.complete(old, prepared)).isInstanceOf(
      JobService.StaleLeaseException.class
    );
    assertThat(mapper.byJob(id).indexPath).isNull();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_document_chunk WHERE document_id=?",
        Integer.class,
        mapper.byJob(id).id
      )
    ).isZero();
    jobs.complete(
      current,
      handler.prepare(current, jobs.beforeExecution(current))
    );
    assertThat(mapper.byJob(id).published).isTrue();
  }

  @Test
  void actualParserLimitIsRecordedAsNonRetryableFailure() throws Exception {
    byte[] large = "a".repeat(2_000_001).getBytes();
    var r = http.upload(
      5,
      large,
      "m.txt",
      "text/plain",
      Map.of("title", "too much"),
      key()
    );
    expect(r, 202, null);
    long id = r.data().path("jobId").asLong();
    owned.add(id);
    var lease = claim(id);
    assertThatThrownBy(() ->
      handler.prepare(lease, jobs.beforeExecution(lease))
    ).isInstanceOf(JobService.StaleLeaseException.class);
    assertThat(jobMapper.find(id).status).isEqualTo("FAILED");
    assertThat(jobMapper.find(id).lastErrorRetryable).isFalse();
    assertThat(
      db.queryForObject(
        "SELECT parsed_char_count FROM t_knowledge_document WHERE latest_job_id=?",
        Integer.class,
        id
      )
    ).isGreaterThan(2_000_000);
    assertThat(mapper.byJob(id).charCount).isNull();
  }

  @Test
  void listValidatesFiltersPaginationAndRole() throws Exception {
    for (String query : List.of(
      "?page=0",
      "?pageSize=101",
      "?page=1&page=2",
      "?documentKey=Bad_Key",
      "?modelTag=bad%20tag",
      "?unknown=x"
    ))
      expect(
        http.json("GET", "/admin/knowledge-documents" + query, 5, null, null),
        400,
        "INVALID_ARGUMENT"
      );
    expect(
      http.json("GET", "/admin/knowledge-documents", 1, null, null),
      403,
      "FORBIDDEN"
    );
  }

  @Test
  void completionStorageFailureRollsBackHeadMetadataAndChunks()
    throws Exception {
    long id = upload(null, key()).data().path("jobId").asLong();
    var lease = claim(id);
    var prepared = handler.prepare(lease, jobs.beforeExecution(lease));
    var source =
      new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:mysql://127.0.0.1:13306/" +
          System.getenv("DB_NAME") +
          "?allowPublicKeyRetrieval=true&sslMode=DISABLED",
        "root",
        System.getenv("DB_ROOT_PASSWORD")
      );
    new JdbcTemplate(source).execute(
      "CREATE TRIGGER d5_fail_complete BEFORE UPDATE ON t_background_job FOR EACH ROW BEGIN IF NEW.status='SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled failure'; END IF; END"
    );
    assertThatThrownBy(() -> jobs.complete(lease, prepared)).isInstanceOf(
      RuntimeException.class
    );
    assertThat(mapper.byJob(id).activeVersion).isNull();
    assertThat(mapper.byJob(id).indexPath).isNull();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_document_chunk WHERE document_id=?",
        Integer.class,
        mapper.byJob(id).id
      )
    ).isZero();
  }

  @Test
  void queueCapacityGateSerializesConcurrentReceivers() throws Exception {
    for (int i = 0; i < 19; i++) upload(null, key());
    var pool = Executors.newFixedThreadPool(2);
    String a = key(),
      b = key();
    try {
      var f1 = pool.submit(() ->
        http.upload(5, bytes, "m.txt", "text/plain", Map.of("title", "x"), a)
      );
      var f2 = pool.submit(() ->
        http.upload(5, bytes, "m.txt", "text/plain", Map.of("title", "x"), b)
      );
      var replies = List.of(f1.get(), f2.get());
      for (var r : replies)
        if (r.data().has("jobId")) owned.add(r.data().path("jobId").asLong());
      assertThat(replies)
        .extracting(r -> r.http().statusCode())
        .containsExactlyInAnyOrder(202, 429);
      assertThat(
        replies
          .stream()
          .filter(r -> r.http().statusCode() == 429)
          .findFirst()
          .get()
          .body()
          .path("error")
          .path("code")
          .asString()
      ).isEqualTo("AI_QUEUE_FULL");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void realDispatcherRunsIndexHandlerWithoutRequestContext() throws Exception {
    long id = upload(null, key()).data().path("jobId").asLong();
    var dispatcher = new JobDispatcher(
      jobs,
      new TaskProperties(
        true,
        1,
        0,
        java.time.Duration.ofSeconds(60),
        java.time.Duration.ofSeconds(10),
        "d5-dispatcher"
      ),
      List.of(handler)
    );
    try {
      dispatcher.scan();
      long deadline =
        System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      while (
          !"SUCCEEDED".equals(jobMapper.find(id).status) &&
          System.nanoTime() < deadline
        )
        Thread.sleep(20);
      assertThat(jobMapper.find(id).status).isEqualTo("SUCCEEDED");
      assertThat(mapper.byJob(id).published).isTrue();
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM t_ai_call_log WHERE scene='KNOWLEDGE_INDEX' AND outcome='SUCCEEDED'",
          Integer.class
        )
      ).isEqualTo(1);
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void durablePerMinuteQuotaSurvivesNewAdmissionInstance() throws Exception {
    upload(null, key());
    var limited = new KnowledgeProperties(
      properties.storage(),
      false,
      properties.embeddingBaseUrl(),
      properties.embeddingModel(),
      properties.dimensions(),
      false,
      properties.chatBaseUrl(),
      properties.chatModel(),
      properties.timeout(),
      100,
      20,
      1
    );
    var restarted = new JobAdmission(admissionMapper, limited);
    assertThatThrownBy(() ->
      new TransactionTemplate(manager).execute(s -> {
        restarted.reserve(5);
        return null;
      })
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED)
    );
  }

  @Test
  void malformedPdfFailsWithoutRetryAndWithoutPublishedChunks()
    throws Exception {
    var r = http.upload(
      5,
      "%PDF-broken".getBytes(),
      "m.pdf",
      "application/pdf",
      Map.of("title", "broken"),
      key()
    );
    expect(r, 202, null);
    long id = r.data().path("jobId").asLong();
    owned.add(id);
    var lease = claim(id);
    assertThatThrownBy(() ->
      handler.prepare(lease, jobs.beforeExecution(lease))
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT)
    );
    jobs.fail(lease, ErrorCode.INVALID_ARGUMENT, false);
    assertThat(mapper.byJob(id).published).isFalse();
    assertThat(jobMapper.find(id).lastErrorRetryable).isFalse();
  }

  @Test
  void orphanCleanupRetainsReferencedRawFileAndCurrentWorkerIndex()
    throws Exception {
    long id = upload(null, key()).data().path("jobId").asLong();
    var lease = claim(id);
    var prepared = handler.prepare(lease, jobs.beforeExecution(lease));
    var raw = java.nio.file.Path.of(mapper.byJob(id).filePath);
    var manifest = files
      .root()
      .resolve("index-" + lease.claimToken() + ".json");
    var orphan = files.root().resolve("index-" + UUID.randomUUID() + ".json");
    java.nio.file.Files.writeString(orphan, "orphan");
    var old = java.nio.file.attribute.FileTime.from(
      java.time.Instant.now().minus(java.time.Duration.ofDays(2))
    );
    for (var path : List.of(raw, manifest, orphan))
      java.nio.file.Files.setLastModifiedTime(path, old);
    // 同一隔离目录可保留前轮的合法孤儿；核对本轮指定文件，而不是假设全目录只有一个。
    assertThat(maintenance.cleanOld()).isGreaterThanOrEqualTo(1);
    assertThat(java.nio.file.Files.exists(raw)).isTrue();
    assertThat(java.nio.file.Files.exists(manifest)).isTrue();
    assertThat(java.nio.file.Files.exists(orphan)).isFalse();
    jobs.complete(lease, prepared);
    assertThat(mapper.byJob(id).published).isTrue();
    // 不把共享原始文件留下旧时间戳，避免干扰其他合成夹具。
    java.nio.file.Files.setLastModifiedTime(
      raw,
      java.nio.file.attribute.FileTime.from(java.time.Instant.now())
    );
    java.nio.file.Files.setLastModifiedTime(
      manifest,
      java.nio.file.attribute.FileTime.from(java.time.Instant.now())
    );
  }
}
