package com.autocare.server.task;

import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.analysis.*;
import com.autocare.server.knowledge.*;
import com.autocare.server.order.lifecycle.OrderLifecycleService;
import com.autocare.server.web.UtcTimes;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** D5接收Location的可用读取入口；管理员始终只看运维投影。 */
@Service
public class JobQueryService {

  private final JobService jobs;
  private final JobMapper mapper;
  private final com.autocare.server.user.UserMapper users;
  private final AnalysisMapper analyses;
  private final AnalysisResults results;
  private final KnowledgeMapper knowledge;

  public JobQueryService(
    JobService jobs,
    JobMapper mapper,
    com.autocare.server.user.UserMapper users,
    AnalysisMapper analyses,
    AnalysisResults results,
    KnowledgeMapper knowledge
  ) {
    this.jobs = jobs;
    this.mapper = mapper;
    this.users = users;
    this.analyses = analyses;
    this.results = results;
    this.knowledge = knowledge;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> get(long actor, long id) {
    var user = users.findById(actor);
    if (user == null || !user.isEnabled()) throw ApiException.of(
      ErrorCode.UNAUTHENTICATED
    );
    Role role = user.getRole();
    JobRow row = role == Role.ADMIN ? mapper.find(id) : jobs.read(actor, id);
    if (row == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    var v = new LinkedHashMap<String, Object>();
    Object result = null;
    String reason = null;
    if ("QUOTE_ANALYSIS".equals(row.type)) {
      var r = analyses.result(row.id);
      reason = results.stale(row, r);
      if (role != Role.ADMIN && "SUCCEEDED".equals(row.status)) result =
        results.result(r);
    } else {
      var d = knowledge.byJob(row.id);
      if (d != null && d.staleReason != null) reason = d.staleReason;
      if (
        d != null && d.published && !Objects.equals(d.activeVersion, d.version)
      ) reason = "SUPERSEDED";
    }
    v.put("jobId", "" + row.id);
    v.put("type", row.type);
    v.put("status", row.status);
    v.put("createdAt", UtcTimes.format(row.createdAt));
    v.put("updatedAt", UtcTimes.format(row.updatedAt));
    v.put("attempts", row.attempts);
    v.put("retryGeneration", row.retryGeneration);
    v.put("nextRunAt", UtcTimes.format(row.nextRunAt));
    v.put(
      "error",
      row.lastErrorCode == null
        ? null
        : Map.of(
            "code",
            row.lastErrorCode,
            "message",
            row.lastErrorMessage,
            "retryable",
            row.lastErrorRetryable
          )
    );
    v.put("result", result);
    v.put("resultStale", reason != null);
    v.put("staleReason", reason);
    return v;
  }
}
