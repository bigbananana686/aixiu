package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.autocare.server.AutoCareServerApplication;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 两次真实应用启动，由@Scheduled处理持久化报价积压，不手动调用scan。 */
class D3RecoveryTest {

  @Test
  void extraExpiryRecoversAcrossTwoApplicationStarts() throws Exception {
    String name = System.getenv("DB_NAME");
    assertThat(name).startsWith("auto_care_g104_");
    var source = new DriverManagerDataSource(
      "jdbc:mysql://127.0.0.1:13306/" +
        name +
        "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
      System.getenv("DB_USERNAME"),
      System.getenv("DB_PASSWORD")
    );
    JdbcTemplate db = new JdbcTemplate(source);
    Flyway.configure()
      .dataSource(source)
      .locations("classpath:db/migration", "classpath:db/seed")
      .placeholders(
        Map.of("seedPasswordHash", System.getenv("SEED_PASSWORD_HASH"))
      )
      .load()
      .migrate();
    List<Long> ids = new ArrayList<>();
    try {
      long first = backlog(db);
      ids.add(first);
      try (var app = start()) {
        awaitExpired(db, first);
      }
      long second = backlog(db);
      ids.add(second);
      try (var app = start()) {
        awaitExpired(db, second);
        assertThat(
          db.queryForObject(
            "SELECT row_version FROM t_service_order WHERE id=?",
            Integer.class,
            first
          )
        ).isEqualTo(6);
      }
      System.out.println(
        "D3 QUOTE_RECOVERY two actual application starts; orders=" +
          ids +
          " SERVICING version6; expiry once; temporary reservation released"
      );
    } finally {
      for (long id : ids) {
        db.update(
          "UPDATE t_service_order SET approved_quote_id=NULL,pending_quote_id=NULL WHERE id=?",
          id
        );
        db.update("DELETE FROM t_order_operation WHERE order_id=?", id);
        db.update("DELETE FROM t_order_state_log WHERE order_id=?", id);
        db.update(
          "UPDATE t_order_quote SET base_approved_quote_id=NULL WHERE order_id=?",
          id
        );
        db.update("DELETE FROM t_order_quote WHERE order_id=?", id);
        db.update("DELETE FROM t_service_order WHERE id=?", id);
      }
    }
  }

  ConfigurableApplicationContext start() {
    return new SpringApplication(AutoCareServerApplication.class).run(
      "--server.port=0",
      "--autocare.order.accept-expiry-enabled=false",
      "--autocare.order.quote-expiry-enabled=true",
      "--autocare.order.quote-expiry-initial-delay-ms=100",
      "--autocare.order.quote-expiry-delay-ms=100"
    );
  }

  long backlog(JdbcTemplate db) {
    String no = "D3-R-" + UUID.randomUUID().toString().substring(0, 20);
    db.update(
      "INSERT INTO t_service_order(order_no,owner_id,vehicle_id,technician_id,state,row_version,service_start,occupied_start,committed_end,effective_end,accept_deadline,booking_items_json,rules_snapshot_json,address_snapshot,mileage_snapshot,model_snapshot,plate_snapshot,created_at,updated_at) VALUES (?,1,1,3,'QUOTE_PENDING',5,UTC_TIMESTAMP(3)+INTERVAL 1 DAY,UTC_TIMESTAMP(3)+INTERVAL 1 DAY-INTERVAL 45 MINUTE,UTC_TIMESTAMP(3)+INTERVAL 1 DAY+INTERVAL 1 HOUR,UTC_TIMESTAMP(3)+INTERVAL 1 DAY+INTERVAL 2 HOUR,UTC_TIMESTAMP(3)-INTERVAL 1 SECOND,'[]','{}','d1 recovery fixture',0,'TEST','***',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
      no
    );
    long id = db.queryForObject(
      "SELECT id FROM t_service_order WHERE order_no=?",
      Long.class,
      no
    );
    db.update(
      "INSERT INTO t_order_quote(order_id,version,purpose,status,total_amount,currency,total_minutes,added_minutes,target_end,expires_at,submitted_by,submitted_at,content_hash) VALUES (?,1,'INITIAL','PENDING',199.00,'CNY',90,0,UTC_TIMESTAMP(3)+INTERVAL 1 DAY+INTERVAL 2 HOUR,UTC_TIMESTAMP(3)-INTERVAL 1 SECOND,3,UTC_TIMESTAMP(3)-INTERVAL 11 MINUTE,REPEAT('a',64))",
      id
    );
    long q = db.queryForObject(
      "SELECT id FROM t_order_quote WHERE order_id=?",
      Long.class,
      id
    );
    db.update(
      "INSERT INTO t_order_quote(order_id,version,purpose,status,total_amount,currency,total_minutes,added_minutes,target_end,expires_at,submitted_by,submitted_at,content_hash) SELECT order_id,2,'EXTRA','PENDING',total_amount,currency,total_minutes,20,target_end,expires_at,submitted_by,submitted_at,content_hash FROM t_order_quote WHERE id=?",
      q
    );
    long extra = db.queryForObject(
      "SELECT id FROM t_order_quote WHERE order_id=? AND version=2",
      Long.class,
      id
    );
    db.update("UPDATE t_order_quote SET status='CONFIRMED' WHERE id=?", q);
    db.update(
      "UPDATE t_order_quote SET base_approved_quote_id=? WHERE id=?",
      q,
      extra
    );
    db.update(
      "UPDATE t_service_order SET approved_quote_id=? WHERE id=?",
      q,
      id
    );
    q = extra;
    db.update(
      "UPDATE t_service_order SET pending_quote_id=? WHERE id=?",
      q,
      id
    );
    return id;
  }

  void awaitExpired(JdbcTemplate db, long id) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < until) {
      if (
        "SERVICING".equals(
          db.queryForObject(
            "SELECT state FROM t_service_order WHERE id=?",
            String.class,
            id
          )
        )
      ) {
        var row = db.queryForMap(
          "SELECT state,row_version,pending_quote_id,approved_quote_id,committed_end,effective_end,reservation_active FROM t_service_order WHERE id=?",
          id
        );
        assertThat(row.get("pending_quote_id")).isNull();
        assertThat(row.get("approved_quote_id")).isNotNull();
        assertThat(row.get("effective_end")).isEqualTo(
          row.get("committed_end")
        );
        assertThat(((Number) row.get("row_version")).intValue()).isEqualTo(6);
        assertThat(row.get("reservation_active").toString()).isIn("true", "1");
        assertThat(
          db.queryForObject(
            "SELECT status FROM t_order_quote WHERE order_id=? AND purpose='EXTRA'",
            String.class,
            id
          )
        ).isEqualTo("EXPIRED");
        assertThat(
          db.queryForObject(
            "SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='EXPIRE_QUOTE'",
            Integer.class,
            id
          )
        ).isEqualTo(1);
        System.out.println("D3 QUOTE_SCHEDULED " + row);
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("scheduled quote expiry did not recover backlog");
  }
}
