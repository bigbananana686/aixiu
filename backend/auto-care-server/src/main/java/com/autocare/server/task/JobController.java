package com.autocare.server.task;

import com.autocare.server.knowledge.KnowledgeInputs;
import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class JobController {

  private final JobQueryService service;

  public JobController(JobQueryService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/jobs/{jobId}")
  public ResponseEntity<?> get(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String jobId,
    @RequestParam MultiValueMap<String, String> query,
    HttpServletRequest request
  ) {
    KnowledgeInputs.query(query, java.util.Set.of());
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(
        new ApiResponse<>(
          RequestIdFilter.currentRequestId(request),
          service.get(
            Long.parseLong(jwt.getSubject()),
            KnowledgeInputs.id(jobId, "jobId")
          )
        )
      );
  }
}
