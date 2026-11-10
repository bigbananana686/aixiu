package com.autocare.server.command;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.FieldErrors;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 幂等键：一个已经过校验的、可以拿去当坐标用的键。
 *
 * <p><b>它管什么。</b>只做一件事——把请求头 {@code Idempotency-Key} 的原始字符串变成一个
 * 「确认合法」的值。不合法就在这儿抛 400，绝不带着一个坏键往下走。
 *
 * <p><b>它不管什么。</b>不查库、不算摘要、不知道 operationId。它只是一个值对象，
 * 存在的意义是让「键已经验过了」这件事在类型上看得出来——下游拿到 {@code IdempotencyKey}
 * 就说明不必再验第二遍。
 *
 * <p><b>为什么只能在这条路径上必填。</b>幂等键分四类用法（公共契约 §5.1）。
 * 本类只服务 {@code REQUIRED_SUCCESS_REPLAY} 那一类——车辆/目录/窗口写、全部状态命令等。
 * 其余三类根本不会走到这里：{@code NOT_APPLICABLE_READ}（GET）压根不调用命令执行器；
 * {@code NOT_SUPPORTED_AUTH}（注册/登录）由更外层的
 * {@code IdempotencyKeyNotSupportedFilter} 直接 400 拦掉。
 * 所以在这个类里，「键缺失」一律是错误态，没有"可选的键"这种分支。
 *
 * <p><b>两条口径别写反</b>（契约 §2 请求头表 + PREP-02 §1.5 字段级子码表）：
 * <ul>
 *   <li>缺失 → 400 {@code INVALID_ARGUMENT}，字段级子码 {@code REQUIRED}；</li>
 *   <li>格式非法 → 同样 400，但子码是 {@code INVALID_FORMAT}（不是 INVALID_TYPE、也不是 OUT_OF_RANGE）。</li>
 * </ul>
 * 两者的字段路径都是 {@code /headers/Idempotency-Key}——<b>请求头字段路径要带 {@code /headers/} 前缀</b>，
 * 这跟 body 字段写 {@code /items/0/quantity} 是同一套 JSON Pointer 写法。
 */
public record IdempotencyKey(String value) {

    /** 请求头名字。契约原文用的就是这一个拼写（连字符，不是下划线）。 */
    public static final String HEADER_NAME = "Idempotency-Key";

    /**
     * 契约给的正则：{@code ^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$}。
     *
     * <p>两条约束合在一起看：首字符必须是字母或数字，后续 15—127 位，合计 <b>16—128 位</b>。
     * 上界 128 不是随便定的，它正好是库里 {@code t_idempotency.idempotency_key VARCHAR(128)} 的宽度——
     * 前端放行一个 129 位的键，最后会在插入时长成 SQL 报错而不是一个干净的 400。
     *
     * <p>字符集只收 ASCII。这就是「16—128 个 ASCII 字符」那句话的落地方式：不用另写一段长度校验，
     * 中文、空格、引号自动出局。
     */
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$");

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 解析并校验请求头里的键。
     *
     * <p><b>不做 trim。</b>契约没有为这个头规定 trim，而静默修剪会制造一个隐蔽问题：
     * 客户端第一次发 {@code " abc…"}、第二次发 {@code "abc…"}，修剪之后会被当成同一个键——
     * 明明是两个不同的字符串，服务端却认成同一次意图。宁可判它格式非法（开头不是字母数字），
     * 也不要偷偷改写客户端发来的字节。这一点和 {@code RequestIdFilter} 处理 {@code X-Request-Id}
     * 的做法不同，那里 trim 是契约明写的。
     *
     * @param raw 请求头的原始值，可以为 null（表示客户端压根没带）
     * @return 合法的键
     * @throws ApiException 缺失（{@code REQUIRED}）或格式非法（{@code INVALID_FORMAT}）
     */
    public static IdempotencyKey parse(String raw) {
        String path = FieldErrors.headerPath(HEADER_NAME);

        if (raw == null || raw.isEmpty()) {
            throw ApiException.invalid("请求头不合法",
                    FieldErrors.required(path, "该操作必须携带幂等键"));
        }
        if (!FORMAT.matcher(raw).matches()) {
            // 说明里把形状说全，客户端照着改一次就能过：长度区间 + 允许的字符集
            throw ApiException.invalid("请求头不合法",
                    FieldErrors.invalidFormat(path,
                            "幂等键须为 16 至 128 个字符，只允许字母、数字与 . _ : - ，且首字符为字母或数字"));
        }
        return new IdempotencyKey(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
