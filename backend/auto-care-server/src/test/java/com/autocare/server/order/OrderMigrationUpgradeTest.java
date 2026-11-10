package com.autocare.server.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OrderMigrationUpgradeTest {

  @Test
  void existing901UpgradesTo905WithoutReseedingOrChangingUserIds() {
    String name = System.getenv("DB_NAME") + "_upgrade";
    assertThat(name).matches("auto_care_g104_[a-z0-9_]+_upgrade");
    String base = "jdbc:mysql://127.0.0.1:13306/";
    String options =
      "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    JdbcTemplate root = new JdbcTemplate(
      new DriverManagerDataSource(
        base + options,
        "root",
        System.getenv("DB_ROOT_PASSWORD")
      )
    );
    // CREATE 不带 IF NOT EXISTS：绝不接管已存在的数据。
    root.execute(
      "CREATE DATABASE " +
        name +
        " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    );
    try {
      root.execute(
        "GRANT ALL PRIVILEGES ON " + name + ".* TO 'autocare_app'@'%'"
      );
      var source = new DriverManagerDataSource(
        base + name + options,
        System.getenv("DB_USERNAME"),
        System.getenv("DB_PASSWORD")
      );
      var placeholders = Map.of(
        "seedPasswordHash",
        System.getenv("SEED_PASSWORD_HASH")
      );
      Flyway.configure()
        .dataSource(source)
        .locations("classpath:db/migration", "classpath:db/seed")
        .placeholders(placeholders)
        .target("901")
        .load()
        .migrate();
      JdbcTemplate db = new JdbcTemplate(source);
      var users = db.queryForList(
        "SELECT id,login_name,role,enabled FROM t_user ORDER BY id"
      );
      var seed = db.queryForList(
        "SELECT version,checksum,installed_on FROM flyway_schema_history WHERE version IN ('900','901') ORDER BY installed_rank"
      );
      var result = Flyway.configure()
        .dataSource(source)
        .locations("classpath:db/migration", "classpath:db/seed")
        .placeholders(placeholders)
        .load()
        .migrate();
      assertThat(result.migrationsExecuted).isEqualTo(4);
      assertThat(
        db.queryForList(
          "SELECT id,login_name,role,enabled FROM t_user WHERE login_name<>'system_accept_expiry' ORDER BY id"
        )
      ).isEqualTo(users);
      assertThat(
        db.queryForList(
          "SELECT version,checksum,installed_on FROM flyway_schema_history WHERE version IN ('900','901') ORDER BY installed_rank"
        )
      ).isEqualTo(seed);
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM t_user WHERE login_name='system_accept_expiry' AND enabled=0",
          Integer.class
        )
      ).isEqualTo(1);
      assertThat(
        db.queryForObject(
          "SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_order_quote' AND column_name='status'",
          String.class
        )
      ).contains("'WITHDRAWN'");
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM t_ai_admission WHERE id=1",
          Integer.class
        )
      ).isEqualTo(1);
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_knowledge_document' AND column_name='index_space'",
          Integer.class
        )
      ).isEqualTo(1);
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('spring_ai_chat_memory','t_ai_conversation','t_ai_conversation_turn')",
          Integer.class
        )
      ).isEqualTo(3);
      System.out.println(
        "G104 MIGRATION upgrade 901->905 migrationsExecuted=" +
          result.migrationsExecuted +
          " seed histories unchanged; original user IDs preserved; disabled system actor added"
      );
    } finally {
      // 只删除本测试刚创建并独占的数据库，无用户数据。
      root.execute("DROP DATABASE " + name);
      root.execute(
        "REVOKE ALL PRIVILEGES ON " + name + ".* FROM 'autocare_app'@'%'"
      );
    }
  }
}
