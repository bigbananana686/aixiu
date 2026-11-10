package com.autocare.server.web;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldError;
import com.autocare.common.error.FieldErrorCode;
import com.autocare.common.error.FieldErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 所有出口的统一落点：把"别处抛出来的东西"翻译成项目错误信封。
 *
 * <p><b>它只做翻译，不做判断。</b>这里不该出现任何业务规则——业务规则属于抛异常的那一段代码。
 * 这个类存在的意义是：不管异常从哪儿来（我们自己抛的、Spring MVC 抛的、Jackson 抛的），
 * 客户端拿到的都是同一个形状，不会漏成 Boot 默认白页或框架自带 JSON。
 *
 * <p><b>覆盖两类来源</b>：
 * <ol>
 *   <li>框架在请求处理途中的抛错：方法不对、媒体类型不对、Accept 不接受、body 读不出来。</li>
 *   <li>未预料的故障——兜成 500，**不含 SQL、堆栈、他人资料**，只留 requestId 供日志关联。</li>
 * </ol>
 *
 * <p>注意每个方法都直接往 response 写字节（见 {@link ApiErrorWriter}），不靠 Spring 做内容协商——
 * 否则 406 这种"协商本身就失败"的场景，信封会被丢掉。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(
    GlobalExceptionHandler.class
  );

  /** 未知字段在 Jackson 异常消息里的形态，2.x / 3.x 一直是这句，多年未变。 */
  private static final Pattern UNRECOGNIZED_FIELD = Pattern.compile(
    "Unrecognized field \"([^\"]+)\""
  );

  /**
   * Jackson 3 的「未知字段」异常全限定名。
   *
   * <p><b>故意按名字判断，不 import 这个类。</b>Jackson 3 把包名整体从 {@code com.fasterxml.jackson.*}
   * 搬到了 {@code tools.jackson.*}（本项目 2026-10-01 读过源码 jar 实证），而"内部异常类"恰恰是最容易
   * 随大版本改名的东西。写成名字判断，将来真改名了，最多退化成"未知字段不再单独标注"（仍然是 400），
   * 而不是整个模块编译不过。
   */
  private static final String UNRECOGNIZED_PROPERTY_CLASS =
    "tools.jackson.databind.exc.UnrecognizedPropertyException";

  private final ApiErrorWriter writer;

  public GlobalExceptionHandler(ApiErrorWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(
    org.springframework.web.multipart.MaxUploadSizeExceededException.class
  )
  public void handleUploadSize(
    HttpServletRequest request,
    HttpServletResponse response,
    Exception ex
  ) throws IOException {
    writer.write(
      request,
      response,
      ErrorCode.PAYLOAD_TOO_LARGE,
      null,
      List.of(),
      Map.of("limitBytes", 10485760)
    );
  }

  @ExceptionHandler({
    org.springframework.web.multipart.MultipartException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
  })
  public void handleMultipart(
    HttpServletRequest request,
    HttpServletResponse response,
    Exception ex
  ) throws IOException {
    writer.write(
      request,
      response,
      ErrorCode.INVALID_ARGUMENT,
      "上传部分不合法",
      List.of(),
      Map.of()
    );
  }

  // ------------------------------------------------------------------ 1. 我们自己抛的业务异常

  @ExceptionHandler(ApiException.class)
  public void handleApiException(
    HttpServletRequest request,
    HttpServletResponse response,
    ApiException ex
  ) throws IOException {
    log.info(
      "业务错误 requestId={} code={} message={}",
      RequestIdFilter.currentRequestId(request),
      ex.errorCode(),
      ex.getMessage()
    );
    writer.write(request, response, ex);
  }

  // ------------------------------------------------------------------ 2. body 读不出来 / 未知字段

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public void handleUnreadable(
    HttpServletRequest request,
    HttpServletResponse response,
    HttpMessageNotReadableException ex
  ) throws IOException {
    // 「注册传了 role」这条拒绝路径就落在这里：契约要求它是 400 + 字段级 UNKNOWN_FIELD，
    // 而不是 403。前提是 Jackson 开着 FAIL_ON_UNKNOWN_PROPERTIES（application-example.yml 里配了）。
    String unknownField = findUnknownField(ex);
    if (unknownField != null) {
      log.info(
        "未知字段 requestId={} field={}",
        RequestIdFilter.currentRequestId(request),
        unknownField
      );
      writer.write(
        request,
        response,
        ErrorCode.INVALID_ARGUMENT,
        "请求字段不合法",
        List.of(FieldErrors.unknownField("/" + unknownField, "不接受的字段")),
        Map.of()
      );
      return;
    }
    // 其余情况：JSON 语法坏了、类型对不上、必填缺了——统一 400，不把 Jackson 原文透给客户端
    log.info(
      "请求体不可解析 requestId={} cause={}",
      RequestIdFilter.currentRequestId(request),
      rootCauseName(ex)
    );
    writer.write(
      request,
      response,
      ErrorCode.INVALID_ARGUMENT,
      "请求体不合法",
      List.of(
        FieldErrors.of(
          "/",
          FieldErrorCode.INVALID_FORMAT,
          "请求体不是合法的 JSON 对象"
        )
      ),
      Map.of()
    );
  }

  // ------------------------------------------------------------------ 3. 协议层：媒体类型 / 协商 / 方法

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public void handleUnsupportedMediaType(
    HttpServletRequest request,
    HttpServletResponse response,
    HttpMediaTypeNotSupportedException ex
  ) throws IOException {
    // 有 body 但不是 application/json
    log.info(
      "媒体类型不支持 requestId={} contentType={}",
      RequestIdFilter.currentRequestId(request),
      ex.getContentType()
    );
    writer.write(
      request,
      response,
      ErrorCode.UNSUPPORTED_MEDIA_TYPE,
      null,
      List.of(),
      Map.of()
    );
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public void handleNotAcceptable(
    HttpServletRequest request,
    HttpServletResponse response,
    HttpMediaTypeNotAcceptableException ex
  ) throws IOException {
    // 注意：这里必须自己写字节。若交给 Spring 序列化，它会因为"产不出客户端要的类型"而不放 body。
    log.info(
      "Accept 协商失败 requestId={}",
      RequestIdFilter.currentRequestId(request)
    );
    writer.write(
      request,
      response,
      ErrorCode.NOT_ACCEPTABLE,
      null,
      List.of(),
      Map.of()
    );
  }

  /**
   * 方法不对。
   *
   * <p><b>405 的口径已由总控裁定，编号 ACCEPT-009。</b>公共契约 §4 的错误目录里没有 405 对应的业务码
   * （目录只覆盖 400/401/403/404/406/409/413/415/429/500/503）。裁定采纳过渡口径：
   * 「路径存在但 HTTP 方法不支持」统一返回 <b>HTTP 405 + {@code error.code = INVALID_ARGUMENT}</b>，
   * 不新增业务码，该口径自 G1 起沿用。理由：§4 的错误目录是封闭的，新增码要动 PREP-01/02/03
   * 三份已 VERIFIED 产物与 {@code openapi.yaml}，成本与收益不成比例；{@code INVALID_ARGUMENT}
   * 的语义（请求本身不合法、不进入业务事务）也说得通。
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public void handleMethodNotSupported(
    HttpServletRequest request,
    HttpServletResponse response,
    HttpRequestMethodNotSupportedException ex
  ) throws IOException {
    log.info(
      "方法不支持 requestId={} method={}",
      RequestIdFilter.currentRequestId(request),
      ex.getMethod()
    );
    // C-19（ACCEPT-011 裁定「应当加」）：RFC 9110 要求 405 带 Allow 头，值为该资源支持的方法。
    // 值直接问异常要，不自己写死一串——写死的话，将来给这个路径加/删方法，这里就悄悄对不上了。
    // 必须在 writer.write 之前设：write 最后会 flushBuffer()，响应一旦提交，再设头就不生效了。
    // （write 内部只 resetBuffer 清 body，不动已设的响应头，所以这里设的能留住。）
    Set<HttpMethod> supported = ex.getSupportedHttpMethods();
    if (supported != null && !supported.isEmpty()) {
      response.setHeader(
        HttpHeaders.ALLOW,
        supported
          .stream()
          .map(HttpMethod::name)
          .sorted()
          .collect(Collectors.joining(", "))
      );
    }
    // 这里**显式给 HTTP 状态 405**，不走「状态由 ErrorCode 决定」那条默认路：
    // §4 目录里没有 405 对应的业务码，而 INVALID_ARGUMENT 的 httpStatus 是 400——
    // 用它当唯一来源就会把 405 压成 400（G0-04 r3 首跑实测到，已冻结在
    // 证据-r3\00-首跑未改代码-405实际回400-快照\）。两个值分开给才与 ACCEPT-009 一致。
    writer.write(
      request,
      response,
      HttpServletResponse.SC_METHOD_NOT_ALLOWED,
      ErrorCode.INVALID_ARGUMENT,
      "该路径不支持此 HTTP 方法",
      List.of(FieldErrors.invalidFormat("/", "请求方法不被支持")),
      Map.of()
    );
  }

  // ------------------------------------------------------------------ 4. 路径不存在

  @ExceptionHandler(NoHandlerFoundException.class)
  public void handleNoHandler(
    HttpServletRequest request,
    HttpServletResponse response,
    NoHandlerFoundException ex
  ) throws IOException {
    // 要让这条路生效，必须在配置里打开 spring.mvc.throw-exception-if-no-handler-found，
    // 并关掉静态资源映射（spring.web.resources.add-mappings=false），否则未匹配的路径会走
    // Boot 自己的 /error 分发，出来的是 Boot 默认 JSON 而不是我们的信封。
    log.info(
      "路径不存在 requestId={} path={}",
      RequestIdFilter.currentRequestId(request),
      ex.getRequestURL()
    );
    writer.write(
      request,
      response,
      ErrorCode.RESOURCE_NOT_FOUND,
      null,
      List.of(),
      Map.of()
    );
  }

  // ------------------------------------------------------------------ 5. Bean Validation（有就翻译，本片主要靠手写校验）

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public void handleBeanValidation(
    HttpServletRequest request,
    HttpServletResponse response,
    MethodArgumentNotValidException ex
  ) throws IOException {
    List<FieldError> fieldErrors = new ArrayList<>();
    ex.getBindingResult()
      .getFieldErrors()
      .forEach(fe -> {
        // getDefaultMessage() 可能为 null，而 FieldError 不允许 message 为 null —— 兜一句再传进去
        String msg =
          fe.getDefaultMessage() == null || fe.getDefaultMessage().isBlank()
            ? FieldErrorCode.INVALID_FORMAT.defaultMessage()
            : fe.getDefaultMessage();
        fieldErrors.add(FieldErrors.invalidFormat("/" + fe.getField(), msg));
      });
    writer.write(
      request,
      response,
      ErrorCode.INVALID_ARGUMENT,
      "请求字段不合法",
      fieldErrors,
      Map.of()
    );
  }

  // ------------------------------------------------------------------ 6. 兜底

  @ExceptionHandler(Exception.class)
  public void handleUnexpected(
    HttpServletRequest request,
    HttpServletResponse response,
    Exception ex
  ) throws IOException {
    String requestId = RequestIdFilter.currentRequestId(request);
    // 堆栈只进日志，绝不进响应体：响应里不带 SQL、类名、堆栈、他人资料
    log.error("未预料故障 requestId={}", requestId, ex);
    writer.write(
      request,
      response,
      ErrorCode.INTERNAL_ERROR,
      null,
      List.of(),
      Map.of()
    );
  }

  // ------------------------------------------------------------------ 工具

  /**
   * 从异常链里认出「未知字段」，并取出字段名；认不出返回 null。
   *
   * <p><b>为什么改成调 API 而不是解析异常文案</b>：第一版是拿异常的 message 去正则捞
   * {@code Unrecognized field "xxx"}，结果 Jackson 3 的文案格式与 2.x 不同，捞不到 ——
   * 实测表现为字段路径输出成 {@code /?}（G0-04 第一次跑注册用例时抓到）。
   * 类型名判断保留着（它是对的），字段名改为直接问异常对象要。
   */
  private static String findUnknownField(Throwable ex) {
    Throwable cursor = ex;
    while (cursor != null) {
      if (cursor instanceof UnrecognizedPropertyException upe) {
        String name = upe.getPropertyName();
        if (name != null && !name.isBlank()) {
          return name;
        }
        // 拿不到就退回文案兜底；两条都落空才算认不出
        Matcher m = UNRECOGNIZED_FIELD.matcher(
          String.valueOf(upe.getMessage())
        );
        return m.find() ? m.group(1) : "?";
      }
      // 类型名兜底：万一将来包名再搬，仍然能认出这一类异常，只是拿不到字段名
      if (UNRECOGNIZED_PROPERTY_CLASS.equals(cursor.getClass().getName())) {
        Matcher m = UNRECOGNIZED_FIELD.matcher(
          String.valueOf(cursor.getMessage())
        );
        return m.find() ? m.group(1) : "?";
      }
      cursor = cursor.getCause();
    }
    return null;
  }

  private static String rootCauseName(Throwable ex) {
    Throwable cursor = ex;
    while (cursor.getCause() != null && cursor.getCause() != cursor) {
      cursor = cursor.getCause();
    }
    return cursor.getClass().getSimpleName();
  }
}
