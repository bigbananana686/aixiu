package com.autocare.server.web;

import com.autocare.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求体大小闸门：超过 64 KiB 直接 413，不往后面放。
 *
 * <p>顺序 {@code -190}——在编号过滤器（-200）之后、Spring Security 链（-100）之前。
 * 放这儿有两个理由：编号要先有，413 的响应才能带上编号；body 是后面才会去读的东西，
 * 让它先过一遍大小关，等于省掉"读完 1GB 再拒绝"。
 *
 * <p><b>为什么不能指望容器</b>：Tomcat 的 maxPostSize 一类限制只管表单参数，
 * JSON body 不在它的管辖范围里——200 KiB 的 JSON 会被它老老实实全收下。
 * 契约要求「正常 JSON body 上限 64 KiB，超出 413 PAYLOAD_TOO_LARGE」，所以这道关必须自己把。
 *
 * <p><b>已知边界（明确写出来，不装作覆盖了）</b>：本过滤器按 {@code Content-Length} 判断，
 * 覆盖"客户端声明了长度"的常规请求（curl --data-binary @file 属于这类）。
 * **不覆盖不带 Content-Length 的分块传输（Transfer-Encoding: chunked）**——
 * 那种情况要包一层计数的 ServletInputStream 才拦得住，本片未做，边界写进回报交总控裁。
 */
@Component
@Order(RequestBodySizeFilter.FILTER_ORDER)
public class RequestBodySizeFilter extends OncePerRequestFilter {

  /** 编号过滤器之后（-200）、安全链之前（-100）。 */
  public static final int FILTER_ORDER = -190;

  /** 契约：正常 JSON body 上限 64 KiB。 */
  public static final long MAX_BODY_BYTES = 64L * 1024L;

  private final ApiErrorWriter writer;

  public RequestBodySizeFilter(ApiErrorWriter writer) {
    this.writer = writer;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain chain
  ) throws ServletException, IOException {
    // 只对专门的上传路由放宽整包上限；Servlet multipart再限制单文件10MiB。
    boolean upload =
      "POST".equals(request.getMethod()) &&
      request
        .getRequestURI()
        .equals(
          request.getContextPath() + "/api/v1/admin/knowledge-documents"
        ) &&
      request.getContentType() != null &&
      request
        .getContentType()
        .toLowerCase(java.util.Locale.ROOT)
        .startsWith("multipart/form-data");
    long limit = upload ? 11L * 1024 * 1024 : MAX_BODY_BYTES;
    if (request.getContentLengthLong() > limit) {
      writer.write(
        request,
        response,
        ErrorCode.PAYLOAD_TOO_LARGE,
        null,
        List.of(),
        Map.of("limitBytes", limit)
      );
      return;
    }
    chain.doFilter(request, response);
  }
}
