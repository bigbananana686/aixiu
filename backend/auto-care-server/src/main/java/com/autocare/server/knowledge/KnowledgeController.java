package com.autocare.server.knowledge;

import com.autocare.common.error.*;
import com.autocare.server.command.CommandResponses;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/knowledge-documents")
public class KnowledgeController {

  private final KnowledgeService service;
  private final AdminAccess admin;

  public KnowledgeController(KnowledgeService service, AdminAccess admin) {
    this.service = service;
    this.admin = admin;
  }

  @ModelAttribute
  public void authorize(@AuthenticationPrincipal Jwt jwt) {
    admin.require(Long.parseLong(jwt.getSubject()));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> upload(
    @AuthenticationPrincipal Jwt jwt,
    @RequestPart(value = "file", required = false) MultipartFile file,
    @RequestParam MultiValueMap<String, String> form,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    HttpServletRequest request
  ) throws Exception {
    var seen = new HashSet<String>();
    for (var part : request.getParts()) {
      if (
        !Set.of("file", "title", "documentKey", "modelTags", "source").contains(
          part.getName()
        )
      ) throw ApiException.invalid(
        FieldErrors.unknownField("/" + part.getName(), "未知上传部分")
      );
      if (!seen.add(part.getName())) throw ApiException.invalid(
        FieldErrors.duplicate("/" + part.getName(), "上传部分不可重复")
      );
    }
    if (request.getQueryString() != null) throw ApiException.invalid(
      FieldErrors.notAllowed("/query", "上传不接受查询参数")
    );
    return CommandResponses.toEntity(
      service.upload(
        Long.parseLong(jwt.getSubject()),
        file,
        form,
        key,
        RequestIdFilter.currentRequestId(request)
      ),
      request
    );
  }

  @GetMapping
  public ResponseEntity<?> list(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam MultiValueMap<String, String> query,
    HttpServletRequest request
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(
        new ApiResponse<>(
          RequestIdFilter.currentRequestId(request),
          service.list(Long.parseLong(jwt.getSubject()), query)
        )
      );
  }
}
