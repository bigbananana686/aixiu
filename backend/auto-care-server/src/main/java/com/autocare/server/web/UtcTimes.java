package com.autocare.server.web;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时间输出格式化的唯一落点。
 *
 * <p>契约要求：**输出统一 UTC，且固定 3 位小数**，例如 {@code 2026-10-20T02:11:00.000Z}。
 * 库里存的是 {@code DATETIME(3)}，连接参数已经把会话时区钉成 UTC，所以读出来的是 UTC 的墙上时间，
 * 这里只需按固定格式输出——不要再做一次时区换算，否则会**多减 8 小时**。
 *
 * <p>为什么强制 ".000" 而不是让格式器自己决定：三位是契约写死的形状，秒整时也必须补零。
 */
public final class UtcTimes {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private UtcTimes() {
    }

    /** 库里读出来的 UTC 墙上时间（DATETIME(3)）→ 契约要求的字符串。 */
    public static String format(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : FORMATTER.format(utcWallClock.atOffset(ZoneOffset.UTC));
    }

    /** 内存里的瞬时 → 契约要求的字符串（用于 expiresAt 这类不落库的时间）。 */
    public static String format(Instant instant) {
        return instant == null ? null : FORMATTER.format(instant);
    }

    /**
     * 「现在」的统一取法：UTC 的墙上时间。
     *
     * <p>注意区分两种 now：<b>这里的 now 是应用时钟</b>，用于写 created_at / updated_at；
     * 而契约里「报价截止、接单截止」那类判定要求的是**数据库的 now**（锁内查询），
     * 两者不能混用——应用时钟与数据库时钟可能不一致。
     */
    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
