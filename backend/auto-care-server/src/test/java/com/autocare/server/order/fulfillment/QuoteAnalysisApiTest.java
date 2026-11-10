package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.autocare.common.error.*;
import com.autocare.server.analysis.*;
import com.autocare.server.knowledge.*;
import com.autocare.server.task.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "autocare.tasks.enabled=false",
    "autocare.knowledge.per-minute=100",
    "autocare.order.accept-expiry-enabled=false",
    "autocare.order.quote-expiry-enabled=false",
  }
)
@Import(D5TestConfiguration.class)
class QuoteAnalysisApiTest extends D3Support {

  @Autowired
  JobService jobs;

  @Autowired
  JobMapper jobMapper;

  @Autowired
  QuoteAnalysisHandler analysisHandler;

  @Autowired
  KnowledgeIndexHandler indexHandler;

  @Autowired
  KnowledgeMapper knowledge;

  @Autowired
  D5TestConfiguration.FakeAnalyzer analyzer;

  @Autowired
  D5TestConfiguration.FakeEmbeddings embeddings;

  @Autowired
  AnalysisMapper analyses;

  D5Http http;
  List<Long> documentJobs = new ArrayList<>();

  @BeforeEach
  void d5Setup() {
    http = new D5Http(port, issuer, json);
    analyzer.mode = "valid";
    analyzer.seen = List.of();
    embeddings.unavailable = false;
  }

  @AfterEach
  void d5Cleanup() {
    db.update("UPDATE t_user SET enabled=1 WHERE id IN (1,3)");
    for (long id : ids) {
      db.update(
        "DELETE r FROM t_quote_analysis_result r JOIN t_order_quote q ON q.id=r.quote_id WHERE q.order_id=?",
        id
      );
      db.update("DELETE FROM t_ai_call_log WHERE order_id=?", id);
      db.update(
        "DELETE j FROM t_background_job j JOIN t_order_quote q ON q.id=j.resource_id WHERE j.type='QUOTE_ANALYSIS' AND q.order_id=?",
        id
      );
    }
    for (long id : documentJobs) {
      String key = knowledge.byJob(id).documentKey;
      db.update(
        "DELETE c FROM t_document_chunk c JOIN t_knowledge_document d ON d.id=c.document_id WHERE d.latest_job_id=?",
        id
      );
      db.update("DELETE FROM t_knowledge_document WHERE latest_job_id=?", id);
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
  }

  void check(D5Http.Reply r, int status, String code) {
    assertThat(r.http().statusCode()).isEqualTo(status);
    if (code != null) assertThat(
      r.body().path("error").path("code").asString()
    ).isEqualTo(code);
  }

  long doc(String series, String tag) throws Exception {
    var form = new HashMap<String, String>();
    form.put("title", "合成机油机滤说明");
    form.put("modelTags", tag);
    if (series != null) form.put("documentKey", series);
    var r = http.upload(
      5,
      "合成资料：机油机滤属于保养项目，需依据车型手册及人工检查确认。".getBytes(
        StandardCharsets.UTF_8
      ),
      "m.md",
      "text/markdown",
      form,
      key()
    );
    check(r, 202, null);
    long id = r.data().path("jobId").asLong();
    documentJobs.add(id);
    return id;
  }

  void index(long id) {
    var lease = jobs.claim("KNOWLEDGE_INDEX", "d5-index");
    assertThat(lease.jobId()).isEqualTo(id);
    jobs.complete(
      lease,
      indexHandler.prepare(lease, jobs.beforeExecution(lease))
    );
  }

  String binding(long q) {
    var row = db.queryForMap(
      "SELECT version,content_hash FROM t_order_quote WHERE id=?",
      q
    );
    return json.writeValueAsString(
      Map.of(
        "quoteId",
        "" + q,
        "version",
        "" + row.get("version"),
        "contentHash",
        row.get("content_hash")
      )
    );
  }

  long submitAnalysis(long order, long quote, long actor) throws Exception {
    var r = http.json(
      "POST",
      "/orders/" + order + "/quote-analysis",
      actor,
      binding(quote),
      key()
    );
    check(r, 202, null);
    return r.data().path("jobId").asLong();
  }

  JobLease claim(long id) {
    var lease = jobs.claim("QUOTE_ANALYSIS", "d5-analysis");
    assertThat(lease.jobId()).isEqualTo(id);
    return lease;
  }

  void analyze(long id) {
    var lease = claim(id);
    jobs.complete(
      lease,
      analysisHandler.prepare(lease, jobs.beforeExecution(lease))
    );
  }

  D5Http.Reply read(long job, long actor) throws Exception {
    return http.json("GET", "/jobs/" + job, actor, null, null);
  }

  @Test
  void ownerAnalysisIsBoundReadOnlyAndReferencesAreRetrievable()
    throws Exception {
    long d = doc(null, "GENERIC");
    index(d);
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    assertThat(jobMapper.find(job).status).isEqualTo("PENDING");
    assertThat(read(job, 1).data().path("result").isNull()).isTrue();
    analyze(job);
    var r = read(job, 1);
    check(r, 200, null);
    assertThat(r.data().path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(r.data().path("result").path("quoteId").asString()).isEqualTo(
      "" + quote
    );
    assertThat(r.data().path("result").path("citations")).hasSize(1);
    assertThat(r.data().path("resultStale").asBoolean()).isFalse();
    assertThat(analyzer.seenQuote.quoteJson()).doesNotContain(
      "synthetic",
      "address",
      "plate",
      "ownerId"
    );
    assertThat(
      db.queryForObject(
        "SELECT state FROM t_service_order WHERE id=?",
        String.class,
        order
      )
    ).isEqualTo("QUOTE_PENDING");
    var list = get("/" + order + "/quotes", 1);
    expect(list, 200, null);
    assertThat(
      list.data().path("items").get(0).path("analysis").path("jobId").asString()
    ).isEqualTo("" + job);
    assertThat(
      list.data().path("items").get(0).path("analysis").has("findings")
    ).isFalse();
    assertThat(
      db.queryForObject(
        "SELECT prompt_tokens FROM t_ai_call_log WHERE quote_id=?",
        Integer.class,
        quote
      )
    ).isNull();
  }

  @Test
  void successfulAcceptanceReplaysSameJobAfterWorkerCompletion()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1);
    String key = key(),
      body = binding(quote);
    var a = http.json(
      "POST",
      "/orders/" + order + "/quote-analysis",
      1,
      body,
      key
    );
    check(a, 202, null);
    long job = a.data().path("jobId").asLong();
    analyze(job);
    var b = http.json(
      "POST",
      "/orders/" + order + "/quote-analysis",
      1,
      body,
      key
    );
    check(b, 202, null);
    assertThat(b.data()).isEqualTo(a.data());
    assertThat(read(job, 1).data().path("status").asString()).isEqualTo(
      "SUCCEEDED"
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_background_job WHERE resource_id=? AND type='QUOTE_ANALYSIS'",
        Integer.class,
        quote
      )
    ).isEqualTo(1);
  }

  @Test
  void foreignActorAssignedTechnicianAndAdminHaveDistinctReadProjections()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    analyze(job);
    check(read(job, 2), 404, "RESOURCE_NOT_FOUND");
    check(read(job, 3), 404, "RESOURCE_NOT_FOUND");
    var admin = read(job, 5);
    check(admin, 200, null);
    assertThat(admin.data().path("result").isNull()).isTrue();
    long techJob = submitAnalysis(order, quote, 3);
    analyze(techJob);
    check(read(techJob, 3), 200, null);
    check(read(techJob, 1), 404, "RESOURCE_NOT_FOUND");
  }

  @Test
  void strictInputRoleObjectAndVersionValidation() throws Exception {
    long order = arrived(),
      quote = publish(order, 1);
    String path = "/orders/" + order + "/quote-analysis";
    check(http.json("POST", path, 5, binding(quote), key()), 403, "FORBIDDEN");
    check(
      http.json("POST", path, 2, binding(quote), key()),
      404,
      "RESOURCE_NOT_FOUND"
    );
    check(
      http.json("POST", path, 1, binding(quote), null),
      400,
      "INVALID_ARGUMENT"
    );
    for (String b : List.of(
      "{}",
      "{\"quoteId\":1}",
      binding(quote).replace("\"version\":\"1\"", "\"version\":\"2\"")
    )) {
      var r = http.json("POST", path, 1, b, key());
      check(
        r,
        b.contains("contentHash") ? 409 : 400,
        b.contains("contentHash")
          ? "QUOTE_VERSION_CONFLICT"
          : "INVALID_ARGUMENT"
      );
    }
    check(
      http.json(
        "POST",
        path,
        1,
        binding(quote).replaceFirst("[a-f0-9]{64}", "0".repeat(64)),
        key()
      ),
      409,
      "QUOTE_VERSION_CONFLICT"
    );
    long foreign = arrived(1),
      fq = publish(foreign, 1);
    check(
      http.json("POST", path, 1, binding(fq), key()),
      404,
      "RESOURCE_NOT_FOUND"
    );
    check(
      http.json(
        "POST",
        path,
        1,
        binding(quote).replace("}", ",\"actorId\":\"2\"}"),
        key()
      ),
      400,
      "INVALID_ARGUMENT"
    );
  }

  @Test
  void replacedQuoteFinishesAsHistoricalAndDoesNotAttachToNewQuote()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    var lease = claim(job);
    var save = analysisHandler.prepare(lease, jobs.beforeExecution(lease));
    long replacement = q(submit(order, "INITIAL", line("base-1", 1, 2)));
    jobs.complete(lease, save);
    var r = read(job, 1);
    assertThat(r.data().path("staleReason").asString()).isEqualTo("SUPERSEDED");
    assertThat(r.data().path("result").path("quoteId").asString()).isEqualTo(
      "" + quote
    );
    var list = get("/" + order + "/quotes", 1)
      .data()
      .path("items");
    assertThat(list.get(0).path("quoteId").asLong()).isEqualTo(replacement);
    assertThat(list.get(0).path("analysis").isNull()).isTrue();
    assertThat(
      list.get(1).path("analysis").path("resultStale").asBoolean()
    ).isTrue();
  }

  @Test
  void evidenceActivationChangesMarkHistoricalResultStale() throws Exception {
    long d1 = doc(null, "GENERIC");
    index(d1);
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    analyze(job);
    String series = knowledge.byJob(d1).documentKey;
    long d2 = doc(series, "GENERIC");
    index(d2);
    var r = read(job, 1);
    assertThat(r.data().path("staleReason").asString()).isEqualTo(
      "EVIDENCE_VERSION_CHANGED"
    );
    assertThat(
      r
        .data()
        .path("result")
        .path("citations")
        .get(0)
        .path("documentVersion")
        .asString()
    ).isEqualTo("1");
  }

  @Test
  void wrongModelUnpublishedAndSupersededDocumentsNeverBecomeCandidates()
    throws Exception {
    long wrong = doc(null, "UNRELATED_CAR");
    index(wrong);
    long pending = doc(null, "GENERIC");
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    analyze(job);
    assertThat(read(job, 1).data().path("result").path("findings")).isEmpty();
    assertThat(analyzer.seen).isEmpty();
    index(pending);
    long current = doc(knowledge.byJob(pending).documentKey, "GENERIC");
    index(current);
    long next = submitAnalysis(order, quote, 1);
    analyze(next);
    assertThat(analyzer.seen).allSatisfy(e -> {
      assertThat(e.documentKey()).isEqualTo(
        knowledge.byJob(current).documentKey
      );
      assertThat(e.documentVersion()).isEqualTo("2");
    });
  }

  @Test
  void forgedCitationFailsWithoutAutomaticRetryOrPersistedResult()
    throws Exception {
    index(doc(null, "GENERIC"));
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    var lease = claim(job);
    analyzer.mode = "forged";
    assertThatThrownBy(() ->
      analysisHandler.prepare(lease, jobs.beforeExecution(lease))
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT)
    );
    jobs.fail(lease, ErrorCode.INVALID_ARGUMENT, false);
    assertThat(analyses.result(job)).isNull();
    assertThat(jobMapper.find(job).status).isEqualTo("FAILED");
    assertThat(jobMapper.find(job).lastErrorRetryable).isFalse();
    var r = read(job, 1);
    check(r, 200, null);
    assertThat(r.data().path("result").isNull()).isTrue();
  }

  @Test
  void lostResourceAccessBlocksExecutionPublicationAndRead() throws Exception {
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 3);
    var lease = claim(job);
    db.update("UPDATE t_service_order SET technician_id=4 WHERE id=?", order);
    try {
      assertThatThrownBy(() -> jobs.beforeExecution(lease)).isInstanceOf(
        ApiException.class
      );
      assertThatThrownBy(() ->
        jobs.complete(lease, r -> {
          throw new AssertionError("must not run");
        })
      ).isInstanceOf(ApiException.class);
      check(read(job, 3), 404, "RESOURCE_NOT_FOUND");
    } finally {
      db.update("UPDATE t_service_order SET technician_id=3 WHERE id=?", order);
    }
  }

  @Test
  void staleTokenCannotSaveCompletedAnalysis() throws Exception {
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    var lease = claim(job);
    var save = analysisHandler.prepare(lease, jobs.beforeExecution(lease));
    db.update(
      "UPDATE t_background_job SET lease_until=UTC_TIMESTAMP(3) WHERE id=?",
      job
    );
    var next = claim(job);
    assertThatThrownBy(() -> jobs.complete(lease, save)).isInstanceOf(
      JobService.StaleLeaseException.class
    );
    assertThat(analyses.result(job)).isNull();
    jobs.complete(
      next,
      analysisHandler.prepare(next, jobs.beforeExecution(next))
    );
    assertThat(analyses.result(job)).isNotNull();
  }

  @Test
  void chatFailureIsRetryableAndDoesNotApproveQuote() throws Exception {
    index(doc(null, "GENERIC"));
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    var lease = claim(job);
    analyzer.mode = "unavailable";
    assertThatThrownBy(() ->
      analysisHandler.prepare(lease, jobs.beforeExecution(lease))
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE)
    );
    jobs.fail(lease, ErrorCode.AI_UNAVAILABLE, true);
    assertThat(read(job, 1).data().path("status").asString()).isEqualTo(
      "RETRY_WAIT"
    );
    assertThat(
      db.queryForObject(
        "SELECT status FROM t_order_quote WHERE id=?",
        String.class,
        quote
      )
    ).isEqualTo("PENDING");
  }

  @Test
  void newlyActivatedSeriesInvalidatesEarlierMissingEvidenceResult()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    analyze(job);
    assertThat(read(job, 1).data().path("resultStale").asBoolean()).isFalse();
    index(doc(null, "GENERIC"));
    assertThat(read(job, 1).data().path("staleReason").asString()).isEqualTo(
      "EVIDENCE_VERSION_CHANGED"
    );
    assertThat(read(job, 1).data().path("result").path("citations")).isEmpty();
  }

  @Test
  void withdrawnQuoteResultIsNoLongerCurrentAndStillHistorical()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1),
      job = submitAnalysis(order, quote, 1);
    analyze(job);
    expect(
      send(
        "POST",
        "/" + order + "/quotes/" + quote + "/withdraw",
        3,
        "{\"version\":\"1\"}",
        key()
      ),
      200,
      null
    );
    var r = read(job, 1);
    assertThat(r.data().path("staleReason").asString()).isEqualTo(
      "NO_LONGER_CURRENT"
    );
    assertThat(r.data().path("result").path("quoteId").asString()).isEqualTo(
      "" + quote
    );
  }
}
