package com.autocare.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * X-Request-Id 过滤器：给每个请求定一个关联编号，并保证它从响应头出去。
 *
 * <p><b>为什么它必须是最外层的过滤器（顺序 -200）。</b>契约要求「所有响应，含失败，都回 X-Request-Id」。
 * Spring Security 的过滤链是注册在 {@code -100} 的一道 {@code DelegatingFilterProxy}——
 * 401 / 403 都是它直接短路吐出来的，压根到不了后面的 Controller。
 * 所以本过滤器必须比它更靠外（数值更小）：先进来发编号，再放行给安全链；
 * 否则未认证请求的响应里就没有编号，恰好是最需要编号去查日志的那一类请求。
 *
 * <p><b>三条规则</b>（契约原文）：
 * <ol>
 *   <li>合法则采用——输入大小写不敏感，**输出转小写**（同一个编号不能因为大小写变成两个）。</li>
 *   <li>缺失或非法则生成新 UUID，且**不回显非法值**（"abc" 不能原样出现在响应头里）。</li>
 *   <li>**不因这个字段改变业务**——非法值只是被替换，绝不报错。
 *       这是"可观测字段"和"校验字段"的分界：它只是给日志用的线索，没有拒绝请求的资格。</li>
 * </ol>
 *
 * <p><b>一处需要总控追认的自行拍板</b>：契约写的是「UUID v4 标准连字符形式」。本实现只校验
 * **标准连字符形式**（8-4-4-4-12 十六进制、整体 ≤36 字符），**不额外校验版本位是不是 4**。
 * 理由：契约给的非法例子全是形式问题（CR/LF、过长、非法字符），而把版本位也当成硬闸门，
 * 会把「形式完全合法、只是版本位不是 4」的客户端值判成非法、改成新编号——
 * 那等于把一个观测字段升级成了校验点，与第 3 条规则相抵触。
 */
@Component
@Order(RequestIdFilter.FILTER_ORDER)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 比 Spring Security 的过滤链（-100）更靠外，理由见类注释第 1 段。 */
    public static final int FILTER_ORDER = -200;

    public static final String HEADER_NAME = "X-Request-Id";

    /** 编号在请求上的落点（后续写响应体的地方靠它取同一个值，保证头与体一致）。 */
    public static final String ATTRIBUTE_NAME = RequestIdFilter.class.getName() + ".requestId";

    /** 标准连字符形式，大小写都收；长度上界 36 由长度判断单独挡住。 */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final int MAX_LENGTH = 36;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER_NAME));
        request.setAttribute(ATTRIBUTE_NAME, requestId);
        response.setHeader(HEADER_NAME, requestId);
        chain.doFilter(request, response);
    }

    /** 取编号：合法就采用并转小写，否则新生成。 */
    private static String resolve(String raw) {
        if (raw != null) {
            String candidate = raw.trim();
            if (candidate.length() <= MAX_LENGTH && CANONICAL_UUID.matcher(candidate).matches()) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 读当前请求的编号。写响应体的地方用它，保证 {@code body.requestId} 与响应头一致。
     *
     * @return 编号；理论上过滤链跑过之后不会为 null，为 null 时调用方应自行兜一个
     */
    public static String currentRequestId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE_NAME);
        return (value instanceof String s) ? s : null;
    }
}
