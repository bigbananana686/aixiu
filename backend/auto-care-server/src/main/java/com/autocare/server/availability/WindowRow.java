package com.autocare.server.availability;

import java.time.LocalDateTime;

/**
 * 一条技师工作窗口（{@code t_technician_window} 的投影）。
 *
 * <p><b>这里的 {@code LocalDateTime} 读作 UTC</b>：库里 DATETIME(3) 存的就是 UTC
 * （G0-03 取证过会话时区 {@code +00:00}），本包统一在「转 Instant」那一步加 UTC 时区，
 * 不在查询层偷偷转——转早了就分不清「数据的时区」和「展示的时区」。
 */
public class WindowRow {

    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(LocalDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(LocalDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }
}
