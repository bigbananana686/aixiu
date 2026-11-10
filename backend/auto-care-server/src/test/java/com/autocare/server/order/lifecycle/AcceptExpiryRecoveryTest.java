package com.autocare.server.order.lifecycle;

import com.autocare.server.AutoCareServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 真启动两次应用：关机积压靠持久化候选恢复，没有内存队列或测试手动调用 scan。 */
class AcceptExpiryRecoveryTest {
    @Test void scheduledEntrypointRecoversBacklogAcrossApplicationRestart() throws Exception {
        String name=System.getenv("DB_NAME");
        assertThat(name).startsWith("auto_care_g104_");
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+name+
                "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        JdbcTemplate db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/seed")
                .placeholders(Map.of("seedPasswordHash", System.getenv("SEED_PASSWORD_HASH"))).load().migrate();
        List<Long> ids=new ArrayList<>();
        try {
            long first=backlog(db); ids.add(first);
            try(ConfigurableApplicationContext app=start()) { awaitCancellation(db,first); }
            long second=backlog(db); ids.add(second);
            assertThat(db.queryForObject("SELECT state FROM t_service_order WHERE id=?",String.class,second)).isEqualTo("RESERVED");
            try(ConfigurableApplicationContext app=start()) {
                awaitCancellation(db,second);
                // 多轮真实调度之后，也不重复迁移第一个候选。
                assertThat(db.queryForObject("SELECT row_version FROM t_service_order WHERE id=?",Integer.class,first)).isEqualTo(2);
            }
            System.out.println("G104 RECOVERY two real application starts; backlog ids="+ids+" each state=CANCELLED version=2; no manual scan invocation");
        } finally {
            for(long id:ids) {
                db.update("DELETE FROM t_order_operation WHERE order_id=?",id);
                db.update("DELETE FROM t_order_state_log WHERE order_id=?",id);
                db.update("DELETE FROM t_service_order WHERE id=?",id);
            }
        }
    }

    private ConfigurableApplicationContext start() {
        SpringApplication app=new SpringApplication(AutoCareServerApplication.class);
        return app.run("--server.port=0","--autocare.order.accept-expiry-enabled=true",
                "--autocare.order.accept-expiry-initial-delay-ms=200","--autocare.order.accept-expiry-delay-ms=100");
    }

    private long backlog(JdbcTemplate db) {
        String no="G104-R-"+UUID.randomUUID().toString().substring(0,20);
        db.update("INSERT INTO t_service_order(order_no,owner_id,vehicle_id,technician_id,state,row_version,service_start,occupied_start,committed_end,effective_end,accept_deadline,booking_items_json,rules_snapshot_json,address_snapshot,mileage_snapshot,model_snapshot,plate_snapshot,created_at,updated_at) "
                +"VALUES (?,1,1,3,'RESERVED',1,UTC_TIMESTAMP(3)+INTERVAL 1 DAY,UTC_TIMESTAMP(3)+INTERVAL 1 DAY-INTERVAL 45 MINUTE,UTC_TIMESTAMP(3)+INTERVAL 1 DAY+INTERVAL 1 HOUR,UTC_TIMESTAMP(3)+INTERVAL 1 DAY+INTERVAL 1 HOUR,UTC_TIMESTAMP(3)-INTERVAL 1 SECOND,'[]','{}','g104-recovery-test',0,'TEST','***',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",no);
        long id=db.queryForObject("SELECT id FROM t_service_order WHERE order_no=?",Long.class,no);
        db.update("INSERT INTO t_order_state_log(order_id,from_state,to_state,action,actor_id,request_id,created_at) VALUES (?,NULL,'RESERVED','CREATE_ORDER',1,?,UTC_TIMESTAMP(3))",id,UUID.randomUUID().toString());
        return id;
    }

    private void awaitCancellation(JdbcTemplate db,long id) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end) {
            if("CANCELLED".equals(db.queryForObject("SELECT state FROM t_service_order WHERE id=?",String.class,id))) {
                Map<String,Object> result=db.queryForMap("SELECT state,row_version,reservation_active FROM t_service_order WHERE id=?",id);
                assertThat(((Number)result.get("row_version")).intValue()).isEqualTo(2);
                assertThat(result.get("reservation_active").toString()).isIn("false","0");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM t_order_state_log WHERE order_id=?",Integer.class,id)).isEqualTo(2);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM t_order_operation WHERE order_id=? AND action='EXPIRE_ACCEPT'",Integer.class,id)).isEqualTo(1);
                System.out.println("G104 SCHEDULED id="+id+" result="+result); return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("scheduled expiry did not process persisted backlog");
    }
}
