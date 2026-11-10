package com.autocare.server.command;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.UUID;

/**
 * 测试公用小工具。全部 {@code static}，不给实例。
 *
 * <p>集中在一处的原因：五个测试类都要「装身份」「造键」「数行数」，各写一遍就会各写岔——
 * 尤其是身份那块，写成 {@code @WithMockUser} 的话所有用例都会莫名其妙 401。
 */
final class TestSupport {

    private TestSupport() {
    }

    /**
     * 造一个<b>真的</b> {@link Jwt} 装进安全上下文。
     *
     * <p>为什么不能用 {@code @WithMockUser}：执行器取身份时判的是
     * {@code principal instanceof Jwt}，{@code @WithMockUser} 给的是 {@code User}，
     * 会直接 401。这里不需要真签名——执行器的职责不是验签（那是安全过滤链的活），
     * 它只读 {@code sub}。
     *
     * <p><b>userId 必须是 t_user 里真有的行</b>：{@code t_idempotency.actor_id} 和
     * {@code t_vehicle.owner_id} 都有外键指着 t_user，编个数字会插入失败。
     */
    static JwtAuthenticationToken jwtAuthentication(long userId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    /** 直接调用执行器的测试用这个：把它装进上下文。 */
    static void installActor(long userId) {
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(userId));
    }

    /** 当前上下文里的 actorId，与执行器同一套取法。 */
    static long currentActorId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return Long.parseLong(((Jwt) principal).getSubject());
    }

    /** 一把新的幂等键。UUID 是契约的建议，36 位、字符集天然合法。 */
    static String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * 把任意字符串转成纯 ASCII：非 ASCII 一律写成 {@code \\uXXXX}，换行写成 {@code \\n}。
     *
     * <p><b>为什么取证行必须过这一道。</b>Windows 下 Java 的 stdout 按 GBK 出字节，
     * 中文进日志会变乱码——而证据文件是要被人拿去比对原文的，乱码等于没留。
     * 转义之后内容一个字符都没丢，只是换了种不会被编码毁掉的写法。
     *
     * <p><b>注释里也必须写两个反斜杠，这不是排版洁癖。</b>Java 的 Unicode 转义在词法分析
     * <b>之前</b>就处理了，而且<b>注释和字符串里一样逃不掉</b>：本文件第一版这段说明里写了
     * 单个反斜杠 + {@code uXXXX}，编译直接报「非法的 Unicode 逃逸」——因为那个 {@code u}
     * 后面跟着的四个 X 不是十六进制数字。写两个反斜杠，第二个前面已有一个反斜杠，
     * 按 JLS 就不构成转义起点，编译期不会再动它。
     */
    static String ascii(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c >= 32 && c < 127) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    /** 按登录名取一个真实用户 id（种子里有 owner1 / owner2 / tech1 / tech2 / admin1）。 */
    static long userId(JdbcTemplate jdbcTemplate, String loginName) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_user WHERE login_name = ?", Long.class, loginName);
        return id == null ? 0L : id;
    }

    /** 测试写进 t_vehicle 的行数。 */
    static long vehicleCount(JdbcTemplate jdbcTemplate) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_vehicle WHERE model_code = ?", Long.class, TestVehicleWriter.TEST_MODEL);
        return n == null ? 0 : n;
    }

    /** 测试写进去的那辆车的主键。只该有一行的时候用。 */
    static long onlyVehicleId(JdbcTemplate jdbcTemplate) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_vehicle WHERE model_code = ?", Long.class, TestVehicleWriter.TEST_MODEL);
        return id == null ? 0L : id;
    }

    /**
     * 重放前的可见性复核（测试用）：这一行还在、而且 {@code owner_id} 就是这位 actor。
     *
     * <p><b>这里是"填内容"，不是"改机制"。</b>执行器只提供位置——它不知道世界上有车这回事；
     * 具体查哪张表、哪一列，由调用方给。真接线时它是 {@code vehicleMapper.isOwnedBy(actorId, vehicleId)}
     * 之类的调用，本片没有那个 Mapper（G1-02a 才写），所以这里直接用 JdbcTemplate，
     * 与 {@link TestVehicleWriter} 同一套理由：test 作用域、不进 fat jar。
     *
     * <p><b>只回答"在不在、是不是你的"，一个字都不多问。</b>库里里程改了、{@code row_version} 变了、
     * 这辆车后来怎么样了——全都不看。那些属于动态业务检查，按契约重放时一律跳过。
     *
     * <p>{@code resourceId} 为 null 时返回 false：本测试里每条命令的结果都是一个 vehicleId，
     * 拿不到它说明登记那一侧有 bug——宁可 404，也不要悄悄放行。
     */
    static ReplayVisibility vehicleVisibility(JdbcTemplate jdbcTemplate) {
        return (actorId, resourceId) -> {
            if (resourceId == null) {
                return false;
            }
            Long n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_vehicle WHERE id = ? AND owner_id = ?",
                    Long.class, resourceId, actorId);
            return n != null && n > 0;
        };
    }

    /** 某个作用域下的幂等记录行数——「失败不留残留」就靠它自证。 */
    static long idempotencyCount(JdbcTemplate jdbcTemplate, long actorId, String operation, String key) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_idempotency WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
                Long.class, actorId, operation, key);
        return n == null ? 0 : n;
    }

    /** 清掉本片写过的测试数据。测试类结束后调用。 */
    static void cleanTestVehicles(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM t_vehicle WHERE model_code = ?", TestVehicleWriter.TEST_MODEL);
    }
}
