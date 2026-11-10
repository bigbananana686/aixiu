package com.autocare.server.probe;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * G0-03 取证探针（默认关，--autocare.probe.enabled=true 打开）。
 *
 * 为什么必须有它：ACCEPT-007 裁决 C 写死了「DB 隔离级别必须取自应用连接内的会话值，
 * 不接受 mysql 客户端或服务端全局默认值」。也就是说，容器全局是 READ-COMMITTED 不算数，
 * 配置文件里写了 transaction-isolation 也不算数——必须是应用自己连接池发出的连接，
 * 在它身上查 @@session.transaction_isolation。
 *
 * 它是取证仪器，不是业务代码：全部只读，或者写在临时表里、跑完就删。
 * G1 之后如果没人需要，整个类可以删掉，不影响任何功能。
 */
@Component
public class DbProbeRunner {

    private final DataSource dataSource;
    private final Environment environment;

    public DbProbeRunner(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        if (!"true".equalsIgnoreCase(environment.getProperty("autocare.probe.enabled"))) {
            return;
        }

        System.out.println("===== G0-03 应用连接内取证探针 BEGIN =====");
        System.out.println("[jvm] user.timezone = " + System.getProperty("user.timezone"));
        System.out.println("[jvm] TimeZone.getDefault().getID() = " + TimeZone.getDefault().getID());

        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            System.out.println("[jdbc] driver            = " + md.getDriverName() + " " + md.getDriverVersion());
            System.out.println("[jdbc] url               = " + md.getURL());
            System.out.println("[jdbc] database          = " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            System.out.println("[jdbc] connect user      = " + md.getUserName());
            System.out.println("[jdbc] default autocommit= " + c.getAutoCommit());

            // ---- 1. 会话/全局隔离级别 + 引擎 + 时区 + 大小写敏感 ----------------
            // 会话值才是「应用连接内」的证据；全局值放一起是为了对比「两者不是一回事」。
            query(c, "SELECT @@session.transaction_isolation AS session_isolation,\n"
                   + "       @@global.transaction_isolation  AS global_isolation,\n"
                   + "       @@default_storage_engine        AS default_engine,\n"
                   + "       @@session.time_zone             AS session_tz,\n"
                   + "       @@global.time_zone              AS global_tz,\n"
                   + "       @@lower_case_table_names        AS lc_table_names");

            // ---- 2. 真列 UTC 读回：种子写入的 created_at --------------------
            // 种子是应用连接写进去的（UTC_TIMESTAMP(3) 在 SQL 端求值，不受驱动时区影响），
            // 读回来必须还是 UTC 且带 3 位小数。
            query(c, "SELECT login_name, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at_utc\n"
                   + "FROM t_user ORDER BY id LIMIT 3");

            // ---- 3. UTC 写读往返（临时表，不落业务表，跑完即删）------------
            utcRoundTrip(c);
        }

        System.out.println("===== G0-03 应用连接内取证探针 END =====");
    }

    /** 打印一条 SQL 的原始结果，每行一值、第一行是列名，便于逐格核对。 */
    private void query(Connection c, String sql) throws Exception {
        System.out.println("[sql] " + sql.replace('\n', ' '));
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            StringBuilder head = new StringBuilder("[out]");
            for (int i = 1; i <= n; i++) {
                head.append('\t').append(rs.getMetaData().getColumnLabel(i));
            }
            System.out.println(head);
            while (rs.next()) {
                StringBuilder row = new StringBuilder("[out]");
                for (int i = 1; i <= n; i++) {
                    row.append('\t').append(rs.getString(i));
                }
                System.out.println(row);
            }
        }
    }

    private void utcRoundTrip(Connection c) throws Exception {
        final LocalDateTime expected = LocalDateTime.of(2026, 10, 21, 12, 34, 56, 789_000_000);
        final String expectedText = "2026-10-21 12:34:56.789";

        try (Statement st = c.createStatement()) {
            st.execute("CREATE TEMPORARY TABLE t_utc_probe (id INT PRIMARY KEY, ts DATETIME(3) NOT NULL)");
        }

        try {
            // 3a. OffsetDateTime 带 +08:00 偏移写入：驱动必须把它归一到连接时区(UTC)再落库。
            //     会话时区若是错的，读回来就会差 8 小时——这正是这条要抓的东西。
            try {
                OffsetDateTime shanghai = OffsetDateTime.of(2026, 10, 21, 20, 34, 56, 789_000_000, ZoneOffset.ofHours(8));
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_utc_probe (id, ts) VALUES (1, ?)")) {
                    ps.setObject(1, shanghai);
                    ps.executeUpdate();
                }
                readBack(c, 1, expected, expectedText, "OffsetDateTime(+08:00) 20:34:56.789 = UTC 12:34:56.789");
            } catch (Exception e) {
                System.out.println("[utc-roundtrip] OffsetDateTime 路径驱动不支持/失败：" + e.getClass().getName()
                        + ": " + e.getMessage());
            }

            // 3b. LocalDateTime 直接写 UTC 墙钟：验证 DATETIME(3) 毫秒精度不丢。
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_utc_probe (id, ts) VALUES (2, ?)")) {
                ps.setObject(1, expected);
                ps.executeUpdate();
            }
            readBack(c, 2, expected, expectedText, "LocalDateTime 直写");
        } finally {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TEMPORARY TABLE t_utc_probe");
            }
        }
    }

    private void readBack(Connection c, int id, LocalDateTime expected, String expectedText, String label)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT ts FROM t_utc_probe WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                LocalDateTime viaGetObject = rs.getObject(1, LocalDateTime.class);
                String viaCastChar = null;
                try (Statement st = c.createStatement();
                     ResultSet rs2 = st.executeQuery("SELECT CAST(ts AS CHAR) FROM t_utc_probe WHERE id = " + id)) {
                    rs2.next();
                    viaCastChar = rs2.getString(1);
                }
                System.out.println("[utc-roundtrip] case     = " + label);
                System.out.println("[utc-roundtrip] expect   = " + expected + " / '" + expectedText + "'");
                System.out.println("[utc-roundtrip] getObject= " + viaGetObject);
                System.out.println("[utc-roundtrip] cast CHAR= '" + viaCastChar + "'");
                System.out.println("[utc-roundtrip] equal(getObject) = " + expected.equals(viaGetObject));
                System.out.println("[utc-roundtrip] equal(cast CHAR) = " + expectedText.equals(viaCastChar));
            }
        }
    }
}
