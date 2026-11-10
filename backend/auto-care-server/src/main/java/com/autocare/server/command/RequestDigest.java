package com.autocare.server.command;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 请求摘要：把「这一次意图」压成 64 位十六进制的 SHA-256。
 *
 * <p>库里的 {@code t_idempotency.request_hash CHAR(64)} 存的就是它。同一个键再来一次，
 * 摘要一样 → 是重试，重放；摘要不同 → 是换了内容的键，409。
 *
 * <p><b>它只做两件事</b>：
 * <ol>
 *   <li><b>结构规范化</b>——对象键按 Unicode 码点升序、无多余空白、数组保序、数字不许出现浮点。</li>
 *   <li><b>算哈希</b>——SHA-256 over UTF-8，输出小写十六进制。</li>
 * </ol>
 *
 * <p><b>它刻意不做第三件事：值本身的规范化。</b>时间统一成 UTC 毫秒、金额固定两位、ID 转成无前导零的
 * 十进制字符串——这些都归<b>字段校验</b>那一步。理由：同一个值只规范化一次，不留第二条路径。
 * 两处都规范化，将来改了一处、忘了另一处，就会出现「同一个请求算出两个摘要」这种最难查的故障。
 * 本类的输入因此约定为<b>已经校验、已经规范</b>的 DTO。
 *
 * <p><b>为什么自己拼 JSON 字节，不用 {@code objectMapper.writeValueAsString}。</b>
 * 摘要必须<b>跨版本稳定</b>：Jackson 升级一次、改个默认转义策略或者数字输出形式，
 * 所有旧记录的 {@code request_hash} 就全部对不上了——库里躺着的成功记录会突然变成"内容不同"，
 * 全体返回 409 的重放失败。所以：用 Jackson 只把 DTO 变成<b>语义树</b>（这一步不涉及字节形态），
 * 树到字节这一段由本类自己写死。
 *
 * <p><b>一类不能进摘要的东西</b>（契约明写）：认证 token、requestId、幂等键自身。
 * 键不进是有道理的——键是作用域的第三个坐标，同一行记录里已经有它了；把它也算进内容，
 * 等于用键去验键。
 *
 * <p><b>敏感提醒</b>：规范串里含完整 body（比如完整车牌原文）。它只能用于算哈希与测试断言，
 * <b>不进日志、不进响应、不进指标</b>。
 */
@Component
public class RequestDigest {

    /**
     * 顶层规范对象的四个固定键。用 {@link TreeMap} 装碎片是为了「以后加键也不会让顺序漂移」——
     * 顺序一旦变了，所有历史摘要立刻失效。
     */
    private static final String KEY_API_VERSION = "apiVersion";
    private static final String KEY_BODY = "body";
    private static final String KEY_OPERATION_ID = "operationId";
    private static final String KEY_PATH_RESOURCES = "pathResources";
    private static final String KEY_QUERY = "query";

    private final ObjectMapper objectMapper;

    public RequestDigest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 算摘要。
     *
     * @param spec 命令身份（operationId + 具名路径资源 + 允许的 query）
     * @param body <b>已校验、已规范</b>的请求体对象；没有 body 的命令传 {@code null}
     * @return 64 个小写十六进制字符
     */
    public String compute(CommandSpec spec, Object body) {
        return sha256Hex(canonical(spec, body));
    }

    /**
     * 规范串本体。
     *
     * <p><b>公开范围只到包内</b>：它给同包测试用来断言「哪些输入必须产生相同/不同的摘要」——
     * 摘要值本身是看不出差异的，只有对着规范串才讲得清「数组换序就变」这类规则。
     * 生产代码只该调 {@link #compute}。
     */
    String canonical(CommandSpec spec, Object body) {
        // 四个碎片各自先渲染成规范 JSON 文本，再按码点序拼进顶层对象。
        // 分开渲染的好处：子结构的规范化与顶层键的顺序互不牵扯。
        Map<String, String> parts = new TreeMap<>(RequestDigest::compareByCodePoint);
        parts.put(KEY_API_VERSION, jsonString(CommandSpec.API_VERSION));
        parts.put(KEY_OPERATION_ID, jsonString(spec.operationId()));
        parts.put(KEY_PATH_RESOURCES, stringMap(spec.pathResources()));
        parts.put(KEY_QUERY, stringMap(spec.queryParams()));
        // body 恒定出现：没有 body 的命令写成 "body":null，而不是把这个键省掉。
        // 只有一条规则，就不存在「省略」和「显式 null」两种规范形式打架的可能。
        parts.put(KEY_BODY, bodyJson(body));

        StringBuilder out = new StringBuilder(256);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : parts.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(jsonString(e.getKey())).append(':').append(e.getValue());
        }
        return out.append('}').toString();
    }

    // ------------------------------------------------------------------ 片段

    /** body：DTO → 语义树 → 规范文本。null 表示"这次命令没有 body"。 */
    private String bodyJson(Object body) {
        if (body == null) {
            return "null";
        }
        return node(objectMapper.valueToTree(body));
    }

    /** {@code Map<String,String>} 渲染成规范 JSON 对象（键按码点序）。这两种映射的值都是已校验的字符串。 */
    private static String stringMap(Map<String, String> map) {
        Map<String, String> sorted = new TreeMap<>(RequestDigest::compareByCodePoint);
        sorted.putAll(map);
        StringBuilder out = new StringBuilder();
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
        }
        return out.append('}').toString();
    }

    /** 语义树 → 规范 JSON 文本。递归。 */
    private static String node(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            // 对象键按 Unicode 码点升序。注意不能靠 Jackson 的排序特性：那是"序列化选项"，
            // 换个大版本就可能换默认值；摘要的字节形态必须由本类说了算。
            Map<String, String> sorted = new TreeMap<>(RequestDigest::compareByCodePoint);
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                sorted.put(e.getKey(), node(e.getValue()));
            }
            StringBuilder out = new StringBuilder();
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(jsonString(e.getKey())).append(':').append(e.getValue());
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            // 数组保序：契约明写「调整行顺序也属于不同摘要」。所以这里绝不排序。
            StringBuilder out = new StringBuilder();
            out.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(node(child));
            }
            return out.append(']').toString();
        }
        // 用 isString()，别用 isTextual()——后者在 Jackson 3.1.5 已标 @Deprecated（我解 jar 方法表查到的）
        if (node.isString()) {
            return jsonString(node.stringValue());
        }
        if (node.isIntegralNumber()) {
            // 走 BigInteger 再 toString()：保证十进制、无指数、无小数点。
            // 直接用 toString() 某些节点会给出 "1.0E+3" 这种形态，同一数值两种写法=两个摘要。
            return node.bigIntegerValue().toString();
        }
        if (node.isFloatingPointNumber()) {
            // 浮点数根本没有唯一写法（1.0 / 1.00 / 1e0），而且契约禁止浮点参与金额与计算。
            // 一旦出现，说明有人在 DTO 里用了 double/BigDecimal —— 那是设计问题，就地炸掉比悄悄算错好。
            throw new IllegalStateException(
                    "摘要树里出现了浮点数：" + node + "。金额、ID、版本必须用字符串承载");
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "true" : "false";
        }
        throw new IllegalStateException("摘要树出现不支持的节点类型：" + node.getNodeType());
    }

    // ------------------------------------------------------------------ 字节

    /**
     * JSON 字符串字面量。转义规则自己写死，理由见类注释「为什么自己拼字节」。
     *
     * <p>只转义 JSON 规范<b>必须</b>转义的：{@code "}、{@code \}、以及 U+0000—U+001F 控制字符。
     * 非 ASCII 一律原样保留（最终按 UTF-8 出字节）——不做 Unicode 折叠，也不把字符改写成
     * 四位十六进制的转义形式：那样同一个字符会出现两种字节形态，摘要就不再唯一。
     *
     * <p><b>这里踩过一个坑，记下来</b>：Java 在词法分析<b>之前</b>先做一遍「Unicode 转义」预处理，
     * 而且它<b>连注释一起扫</b>。所以注释里一旦出现「反斜杠紧跟 u」那种写法，就会被当成一个
     * 十六进制转义序列去解析，凑不出四位十六进制就报「非法的 Unicode 逃逸」，编译直接失败——
     * 更坑的是报出来的行号会指到别的行（实测指到了第 7 行的 import），看着像 import 出了问题。
     * 本类的注释因此刻意绕开那个形态。
     */
    static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** 按 Unicode <b>码点</b>比较，不是按 UTF-16 code unit。 */
    static int compareByCodePoint(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // 每个合规 JVM 都必须提供 SHA-256；拿不到说明运行环境坏了，不是业务失败
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }
}
