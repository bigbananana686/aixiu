package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.autocare.common.error.ApiException;
import com.autocare.server.task.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

class JobQuoteAuthorizationTest extends D3Support {

  @Autowired
  JobService jobs;

  List<Long> jobIds = new ArrayList<>();

  @AfterEach
  void removeJobs() {
    for (long id : jobIds)
      db.update("DELETE FROM t_background_job WHERE id=?", id);
  }

  JobLease createAnalysis(long order, long quote, long actor) {
    var q = db.queryForMap(
      "SELECT version,content_hash FROM t_order_quote WHERE id=?",
      quote
    );
    JobRow row = new JobRow();
    row.type = "QUOTE_ANALYSIS";
    row.actorId = actor;
    row.resourceId = quote;
    row.businessKey = "d4-quote:" + UUID.randomUUID();
    row.payload = json.writeValueAsString(
      Map.of(
        "quote_id",
        String.valueOf(quote),
        "quote_version",
        q.get("version"),
        "content_hash",
        q.get("content_hash")
      )
    );
    long job = jobs.enqueue(row);
    jobIds.add(job);
    return jobs.claim("QUOTE_ANALYSIS", "identity-worker");
  }

  @Test
  void ownerLosesResourceBetweenExecutionAndResult_noWriteAndNoRead()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1);
    var lease = createAnalysis(order, quote, 1);
    jobs.beforeExecution(lease);
    db.update("UPDATE t_service_order SET owner_id=2 WHERE id=?", order);
    assertThatThrownBy(() ->
      jobs.complete(lease, r -> {
        throw new AssertionError("Result callback must not run");
      })
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> jobs.read(1, lease.jobId())).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void technicianReassignmentBeforeExecution_deniesPersistedActor()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1);
    var lease = createAnalysis(order, quote, 3);
    db.update("UPDATE t_service_order SET technician_id=4 WHERE id=?", order);
    assertThatThrownBy(() -> jobs.beforeExecution(lease)).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void crossRequestWorkerUsesPersistedActorWithoutThreadLocal()
    throws Exception {
    long order = arrived(),
      quote = publish(order, 1);
    var lease = createAnalysis(order, quote, 1);
    var pool = Executors.newSingleThreadExecutor();
    try {
      assertThat(
        pool
          .submit(() -> {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            return jobs.beforeExecution(lease).actorId;
          })
          .get(5, TimeUnit.SECONDS)
      ).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
