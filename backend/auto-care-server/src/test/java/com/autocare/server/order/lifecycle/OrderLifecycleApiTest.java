package com.autocare.server.order.lifecycle;

import com.autocare.server.order.booking.OrderMapper;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.identity.Role;
import com.autocare.server.security.JwtTokenIssuer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** 真实 TCP HTTP、真实 JWT 过滤器、真实 MySQL；不使用测试级回滚隐藏提交结果。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "autocare.order.accept-expiry-enabled=false")
class OrderLifecycleApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenIssuer issuer;
    @Autowired AcceptExpiryService expiry;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired OrderLifecycleMapper lifecycleMapper;
    @Autowired OrderMapper orderMapper;
    @Autowired PlatformTransactionManager manager;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> ids = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private LocalDate day;

    @BeforeEach void setup() {
        // 明确隔离库闸门；触发器和清理不会接触生产库。
        assertThat(db.queryForObject("SELECT DATABASE()", String.class)).startsWith("auto_care_g104_");
        day = db.queryForObject("SELECT DATE(MIN(window_start)) FROM t_technician_window WHERE technician_id=3 AND window_start > UTC_TIMESTAMP(3)", LocalDate.class);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        db.execute("DROP TRIGGER IF EXISTS g104_fail_success");
        for (long id : ids) {
            db.update("DELETE FROM t_order_operation WHERE order_id=?", id);
            db.update("DELETE FROM t_order_state_log WHERE order_id=?", id);
            db.update("DELETE FROM t_service_order WHERE id=?", id);
        }
        for (String key : keys) db.update("DELETE FROM t_idempotency WHERE idempotency_key=?", key);
    }

    @Test void completeHttpJourneyAndPersistedDetail() throws Exception {
        long id = create();
        Reply accept = command(id, "accept", 3, "1", key(), null);
        expect(accept, 200, null);
        assertThat(accept.data().path("acceptDeadline").asString()).endsWith("Z");
        String departKey=key(), arriveKey=key();
        Reply departed=command(id, "depart", 3, "2", departKey, null);
        Reply arrived=command(id, "arrive", 3, "3", arriveKey, null);
        expect(departed, 200, null);
        expect(arrived, 200, null);
        assertThat(command(id,"depart",3,"2",departKey,null).data()).isEqualTo(departed.data());
        assertThat(command(id,"arrive",3,"3",arriveKey,null).data()).isEqualTo(arrived.data());
        assertThat(expiry.expire(id)).isFalse();
        Reply detail = get("/"+id, 3);
        expect(detail, 200, null);
        assertThat(detail.data().path("state").asString()).isEqualTo("ARRIVED");
        assertThat(detail.data().path("rowVersion").asString()).isEqualTo("4");
        assertThat(detail.data().path("actualStart").isNull()).isTrue();
        assertThat(detail.data().path("actualFinish").isNull()).isTrue();
        assertThat(detail.data().path("stateLog").size()).isEqualTo(4);
        assertThat(detail.data().path("operations").size()).isEqualTo(3);
        assertThat(detail.data().path("bookingItems").get(0).path("serviceItemId").asString()).isEqualTo("1");
        assertThat(detail.data().path("address").asString()).isEqualTo("g104-test-address");
        assertThat(detail.response.headers().firstValue("Cache-Control")).contains("no-store");
        expect(command(id, "cancel", 1, "4", key(), null), 409, "ORDER_STATE_CONFLICT");
        assertInvariant(id, "ARRIVED", 4, true, 3);
    }

    @Test void declineAndBothOwnerCancellationBranchesReleaseWithoutChangingBoundaries() throws Exception {
        long id = create();
        Map<String,Object> before = db.queryForMap("SELECT committed_end,effective_end FROM t_service_order WHERE id=?", id);
        String k = key();
        expect(command(id, "decline", 3, "1", k, "  resource unavailable  "), 200, null);
        Reply replay = command(id, "decline", 3, "1", k, "resource unavailable");
        expect(replay, 200, null);
        assertThat(replay.response.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(db.queryForObject("SELECT payload_summary FROM t_order_operation WHERE order_id=?", String.class, id)).isEqualTo("resource unavailable");
        assertThat(db.queryForMap("SELECT committed_end,effective_end FROM t_service_order WHERE id=?", id)).isEqualTo(before);
        assertInvariant(id, "CANCELLED", 2, false, 1);
        long reserved = create();
        String cancelKey=key();
        Reply cancelled=command(reserved, "cancel", 1, "1", cancelKey, null);
        expect(cancelled, 200, null);
        assertThat(command(reserved,"cancel",1,"1",cancelKey,null).data()).isEqualTo(cancelled.data());
        assertInvariant(reserved, "CANCELLED", 2, false, 1);
        long accepted = create();
        expect(command(accepted, "accept", 3, "1", key(), null), 200, null);
        expect(command(accepted, "cancel", 1, "2", key(), null), 200, null);
        assertInvariant(accepted, "CANCELLED", 3, false, 2);
    }

    @Test void queryScopePrivacyPaginationSortAndNoIdempotencySideEffects() throws Exception {
        long first = create();
        expect(command(first,"cancel",1,"1",key(),null),200,null);
        long second = create();
        int before = db.queryForObject("SELECT COUNT(*) FROM t_idempotency", Integer.class);
        Reply list = get("?pageSize=1&state=RESERVED,CANCELLED", 1);
        expect(list, 200, null);
        assertThat(list.data().path("total").asLong()).isEqualTo(2);
        assertThat(list.data().path("items").get(0).path("orderId").asString()).isEqualTo(String.valueOf(second));
        assertThat(list.data().path("items").get(0).has("address")).isFalse();
        assertThat(list.data().path("items").get(0).has("plateMasked")).isFalse();
        assertThat(get("?page=2&pageSize=1",3).data().path("items").get(0).path("orderId").asString()).isEqualTo(String.valueOf(first));
        assertThat(get("",2).data().path("total").asLong()).isZero();
        assertThat(get("",4).data().path("items").size()).isZero();
        expect(get("/"+first,1),200,null);
        expect(get("/"+first,2),404,"RESOURCE_NOT_FOUND");
        expect(get("/"+first,4),404,"RESOURCE_NOT_FOUND");
        JsonNode hidden = get("/"+first,2).body.path("error");
        assertThat(get("/9223372036854775807",2).body.path("error")).isEqualTo(hidden);
        String created = get("/"+second,1).data().path("createdAt").asString();
        assertThat(get("?to="+created,1).data().path("total").asLong()).isEqualTo(1);
        assertThat(get("?from="+created,1).data().path("total").asLong()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM t_idempotency", Integer.class)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"page=0","page=10001","pageSize=101","state=BAD","state=RESERVED,", "ownerId=2", "technicianId=3", "sort=id", "page=1&page=2", "from=2026-01-01", "from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z", "from=2026-01-01T00:00:00Z&to=2026-01-01T00:00:00Z"})
    void invalidQueryIsRejected(String query) throws Exception { expect(get("?"+query,1),400,"INVALID_ARGUMENT"); }

    @Test void rolesAndVisibilityTakePriorityOverDynamicChecksAndJwtRoleCopy() throws Exception {
        long id = create();
        for (String action : List.of("accept","decline","depart","arrive")) {
            expect(command(id,action,1,"1",key(), action.equals("decline")?"reason":null),403,"FORBIDDEN");
            expect(command(id,action,4,"1",key(), action.equals("decline")?"reason":null),404,"RESOURCE_NOT_FOUND");
        }
        expect(command(id,"cancel",3,"1",key(),null),403,"FORBIDDEN");
        expect(command(id,"cancel",2,"1",key(),null),404,"RESOURCE_NOT_FOUND");
        expect(get("",5),403,"FORBIDDEN");
        expect(get("/"+id,5),403,"FORBIDDEN");
        expect(send("POST","/"+id+"/accept",1,"{\"expectedRowVersion\":0}",key(),"application/json","application/json"),403,"FORBIDDEN");
        expect(send("POST","/"+id+"/accept",1,"{\"unexpected\":true}",key(),"application/json","application/json"),403,"FORBIDDEN");
        expect(send("POST","/"+id+"/cancel",3,"{malformed",key(),"application/json","application/json"),403,"FORBIDDEN");
        // 旧 JWT role=OWNER，但数据库仍是 TECH；授权必须以当前账号为准。
        String token = issuer.issue(3, Role.OWNER, Instant.now());
        expect(sendToken("POST","/"+id+"/accept",token,"{\"expectedRowVersion\":\"1\"}",key(),"application/json","application/json"),200,null);
    }

    @ParameterizedTest @ValueSource(strings={"{}","{\"expectedRowVersion\":null}","{\"expectedRowVersion\":1}","{\"expectedRowVersion\":\"0\"}","{\"expectedRowVersion\":\"03\"}","{\"expectedRowVersion\":\"2147483648\"}","{\"expectedRowVersion\":\"1\",\"reason\":\"extra\"}"})
    void invalidVersionsAndUnknownFields(String body) throws Exception {
        long id=create(); expect(send("POST","/"+id+"/accept",3,body,key(),"application/json","application/json"),400,"INVALID_ARGUMENT");
    }

    @ParameterizedTest @ValueSource(strings={"null","12","\"   \"","\"bad\\nreason\""})
    void invalidReasons(String reason) throws Exception {
        long id=create();
        expect(send("POST","/"+id+"/decline",3,"{\"expectedRowVersion\":\"1\",\"reason\":"+reason+"}",key(),"application/json","application/json"),400,"INVALID_ARGUMENT");
    }

    @Test void mediaHeadersPathsMissingReasonAndKeyValidation() throws Exception {
        long id=create();
        String body="{\"expectedRowVersion\":\"1\"}";
        expect(send("POST","/"+id+"/decline",3,body,key(),"application/json","application/json"),400,"INVALID_ARGUMENT");
        expect(command(id,"decline",3,"1",key(),"x".repeat(501)),400,"INVALID_ARGUMENT");
        expect(send("POST","/"+id+"/accept",3,body,null,"application/json","application/json"),400,"INVALID_ARGUMENT");
        expect(send("POST","/"+id+"/accept",3,body,"short","application/json","application/json"),400,"INVALID_ARGUMENT");
        expect(send("POST","/"+id+"/accept",3,body,key(),"text/plain","application/json"),415,"UNSUPPORTED_MEDIA_TYPE");
        expect(send("POST","/"+id+"/accept",3,body,key(),"application/json","text/html"),406,"NOT_ACCEPTABLE");
        for (String path : List.of("01","0","9223372036854775808","abc")) expect(get("/"+path,1),400,"INVALID_ARGUMENT");
        expect(sendToken("GET","",null,null,null,null,"application/json"),401,"UNAUTHENTICATED");
    }

    @Test void historicalReplayPrecedesStateVersionAndDeadlineAndPathIsInDigest() throws Exception {
        long id=create(); String k=key();
        Reply first=command(id,"accept",3,"1",k,null); expect(first,200,null);
        expect(command(id,"depart",3,"2",key(),null),200,null);
        db.update("UPDATE t_service_order SET accept_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",id);
        Reply replay=command(id,"accept",3,"1",k,null); expect(replay,200,null);
        assertThat(replay.data()).isEqualTo(first.data());
        assertThat(replay.response.headers().firstValue("Original-Request-Id")).contains(first.body.path("requestId").asString());
        expect(command(id,"accept",3,"2",k,null),409,"IDEMPOTENCY_KEY_REUSED");
        long other=createAt(1);
        expect(command(other,"accept",3,"1",k,null),409,"IDEMPOTENCY_KEY_REUSED");
        assertInvariant(id,"EN_ROUTE",3,true,2);
    }

    @Test void statePrecedesVersionAndFailureLeavesKeyRetryable() throws Exception {
        long id=create(); String k=key();
        expect(command(id,"accept",3,"2",k,null),409,"ORDER_VERSION_CONFLICT");
        assertThat(keyRows(k)).isZero();
        expect(command(id,"accept",3,"1",k,null),200,null);
        expect(command(id,"accept",3,"1",key(),null),409,"ORDER_STATE_CONFLICT");
        expect(command(id,"depart",3,"1",key(),null),409,"ORDER_VERSION_CONFLICT");
        expect(command(id,"arrive",3,"999",key(),null),409,"ORDER_STATE_CONFLICT");
    }

    @Test void replayRechecksCurrentAccountAndCurrentObjectAccess() throws Exception {
        long id=create(); String k=key();
        expect(command(id,"cancel",1,"1",k,null),200,null);
        try {
            db.update("UPDATE t_service_order SET owner_id=2 WHERE id=?",id);
            expect(command(id,"cancel",1,"1",k,null),404,"RESOURCE_NOT_FOUND");
        } finally { db.update("UPDATE t_service_order SET owner_id=1 WHERE id=?",id); }
        expect(command(id,"cancel",1,"1",k,null),200,null);
        try {
            db.update("UPDATE t_user SET enabled=0 WHERE id=1");
            expect(command(id,"cancel",1,"1",k,null),401,"UNAUTHENTICATED");
        } finally { db.update("UPDATE t_user SET enabled=1 WHERE id=1"); }
        assertInvariant(id,"CANCELLED",2,false,1);
    }

    @Test void expiredBeforeScannerAndAfterScannerHaveSameErrorAndOnlyOneRelease() throws Exception {
        long id=create();
        db.update("UPDATE t_service_order SET accept_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",id);
        expect(command(id,"accept",3,"999",key(),null),409,"ORDER_ACCEPT_EXPIRED");
        assertInvariant(id,"RESERVED",1,true,0);
        assertThat(expiry.scan()).isEqualTo(1);
        assertThat(expiry.scan()).isZero();
        assertInvariant(id,"CANCELLED",2,false,0);
        expect(command(id,"accept",3,"1",key(),null),409,"ORDER_ACCEPT_EXPIRED");
        long ordinary=create();
        expect(command(ordinary,"cancel",1,"1",key(),null),200,null);
        db.update("UPDATE t_service_order SET accept_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",ordinary);
        expect(command(ordinary,"accept",3,"1",key(),null),409,"ORDER_STATE_CONFLICT");
    }

    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void exactDeadlineBoundaryUsesDatabaseSessionTime(int offsetMillis) throws Exception {
        long id=create(); String k=key();
        LocalDateTime deadline = db.queryForObject("SELECT UTC_TIMESTAMP(3)+INTERVAL 2 MINUTE", LocalDateTime.class).withNano(0);
        db.update("UPDATE t_service_order SET accept_deadline=? WHERE id=?",deadline,id);
        LocalDateTime now=deadline.plusNanos(offsetMillis*1_000_000L);
        installTech();
        TransactionTemplate tx=new TransactionTemplate(manager);
        Supplier<Object> run=()->tx.execute(status->{
            try {
                db.execute("SET timestamp="+java.math.BigDecimal.valueOf(now.toInstant(ZoneOffset.UTC).toEpochMilli(),3).toPlainString());
                assertThat(db.queryForObject("SELECT UTC_TIMESTAMP(3)",LocalDateTime.class)).isEqualTo(now);
                System.out.println("G104 BOUNDARY deadline="+deadline+" dbNow="+now+" offsetMs="+offsetMillis);
                return lifecycle.execute(3,String.valueOf(id),OrderAction.ACCEPT,version(1),k,UUID.randomUUID().toString());
            } finally { db.execute("SET timestamp=DEFAULT"); }
        });
        if (offsetMillis<0) { run.get(); assertInvariant(id,"ACCEPTED",2,true,1); }
        else {
            assertThatThrownBy(run::get).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.ORDER_ACCEPT_EXPIRED));
            assertInvariant(id,"RESERVED",1,true,0); assertThat(keyRows(k)).isZero();
        }
    }

    @Test void failureAfterBusinessWritesRollsBackStateLogsOperationsAndIdempotency() throws Exception {
        long id=create(); String k=key();
        // binary log 开启时 CREATE TRIGGER 需要特权。仅用 root 给隔离库装故障探针，
        // 实际 HTTP 写仍走应用账号；不提升应用账号或修改服务器全局变量。
        var rootSource = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+System.getenv("DB_NAME")
                +"?sslMode=DISABLED&allowPublicKeyRetrieval=true", "root", System.getenv("DB_ROOT_PASSWORD"));
        new JdbcTemplate(rootSource).execute("CREATE TRIGGER g104_fail_success BEFORE UPDATE ON t_idempotency FOR EACH ROW "
                + "BEGIN IF NEW.operation='acceptOrder' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='G104 injected failure after business writes'; END IF; END");
        expect(command(id,"accept",3,"1",k,null),500,"INTERNAL_ERROR");
        assertInvariant(id,"RESERVED",1,true,0);
        assertThat(keyRows(k)).isZero();
        db.execute("DROP TRIGGER g104_fail_success");
        expect(command(id,"accept",3,"1",k,null),200,null);
        assertInvariant(id,"ACCEPTED",2,true,1);
    }

    @Test void orderInvariantIgnoresVehicleCommandsWithSameResourceId() throws Exception {
        long id=create();
        // resource_id is scoped by operation; a vehicle may have the same numeric ID.
        db.update("INSERT INTO t_idempotency(actor_id,operation,idempotency_key,request_hash,response_code,response_json,resource_id,created_at) "
                + "VALUES (1,'createVehicle',?,REPEAT('a',64),201,'{}',?,UTC_TIMESTAMP(3))", key(), id);
        assertInvariant(id,"RESERVED",1,true,0);
        expect(command(id,"accept",3,"1",key(),null),200,null);
        assertInvariant(id,"ACCEPTED",2,true,1);
    }

    @Test void mysqlConcurrencyAcceptAgainstCancel() throws Exception {
        long id=create();
        List<Reply> replies=race(()->uncheckedCommand(id,"accept",3,"1",keyThreadSafe()),()->uncheckedCommand(id,"cancel",1,"1",keyThreadSafe()));
        assertThat(replies.stream().filter(r->r.status()==200).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r->r.status()==409).count()).isEqualTo(1);
        replies.stream().filter(r->r.status()==409).forEach(r->assertThat(r.body.path("error").path("code").asString()).isIn("ORDER_STATE_CONFLICT","ORDER_VERSION_CONFLICT"));
        String state=db.queryForObject("SELECT state FROM t_service_order WHERE id=?",String.class,id);
        assertInvariant(id,state,2,!state.equals("CANCELLED"),1);
    }

    @Test void mysqlConcurrencyDepartAgainstCancel() throws Exception {
        long id=create(); expect(command(id,"accept",3,"1",key(),null),200,null);
        List<Reply> replies=race(()->uncheckedCommand(id,"depart",3,"2",keyThreadSafe()),()->uncheckedCommand(id,"cancel",1,"2",keyThreadSafe()));
        assertThat(replies.stream().filter(r->r.status()==200).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r->r.status()==409).count()).isEqualTo(1);
        replies.stream().filter(r->r.status()==409).forEach(r->assertThat(r.body.path("error").path("code").asString()).isEqualTo("ORDER_STATE_CONFLICT"));
        String state=db.queryForObject("SELECT state FROM t_service_order WHERE id=?",String.class,id);
        assertInvariant(id,state,3,!state.equals("CANCELLED"),2);
    }

    @Test void mysqlConcurrentDuplicateKeyHasOneCommitAndOneReplay() throws Exception {
        long id=create(); String k=key();
        List<Reply> replies=race(()->uncheckedCommand(id,"accept",3,"1",k),()->uncheckedCommand(id,"accept",3,"1",k));
        assertThat(replies).allSatisfy(r->assertThat(r.status()).isEqualTo(200));
        assertThat(replies.stream().filter(r->r.response.headers().firstValue("Idempotency-Replayed").isPresent()).count()).isEqualTo(1);
        assertThat(keyRows(k)).isEqualTo(1); assertInvariant(id,"ACCEPTED",2,true,1);
    }

    @Test void mysqlAcceptAgainstExpiryAndTwoScannersUseOneTransition() throws Exception {
        long beforeDeadline=create();
        List<Object> early=race(()->uncheckedCommand(beforeDeadline,"accept",3,"1",keyThreadSafe()),()->expiry.expire(beforeDeadline));
        expect((Reply)early.get(0),200,null); assertThat(early.get(1)).isEqualTo(false);
        assertInvariant(beforeDeadline,"ACCEPTED",2,true,1);
        expect(command(beforeDeadline,"cancel",1,"2",key(),null),200,null);
        long id=create(); db.update("UPDATE t_service_order SET accept_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",id);
        List<Object> results=race(()->uncheckedCommand(id,"accept",3,"1",keyThreadSafe()),()->expiry.expire(id));
        expect((Reply)results.get(0),409,"ORDER_ACCEPT_EXPIRED"); assertThat(results.get(1)).isEqualTo(true);
        assertInvariant(id,"CANCELLED",2,false,0);
        long another=create(); db.update("UPDATE t_service_order SET accept_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",another);
        AcceptExpiryService secondInstance=new AcceptExpiryService(lifecycleMapper,orderMapper,lifecycle,manager);
        List<Boolean> scans=race(()->expiry.expire(another),()->secondInstance.expire(another));
        assertThat(scans).containsExactlyInAnyOrder(true,false); assertInvariant(another,"CANCELLED",2,false,0);
    }

    @Test void applicationConnectionSettingsAreActualMysqlRcUtcInnoDb() {
        Map<String,Object> session=db.queryForMap("SELECT VERSION() version, DATABASE() db, @@session.transaction_isolation isolation_level, @@session.time_zone timezone, @@session.innodb_lock_wait_timeout lock_wait");
        System.out.println("G104 DB_SESSION "+session);
        assertThat(session.get("version").toString()).startsWith("8.4.");
        assertThat(session.get("isolation_level")).isEqualTo("READ-COMMITTED");
        assertThat(session.get("timezone").toString()).isIn("+00:00","UTC");
        assertThat(session.get("lock_wait").toString()).isEqualTo("5");
        assertThat(db.queryForObject("SELECT ENGINE FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='t_service_order'",String.class)).isEqualTo("InnoDB");
    }

    private long create() throws Exception { return createAt(0); }
    private long createAt(int dayOffset) throws Exception {
        Reply r=send("POST","",1,"{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\""+day.plusDays(dayOffset)+"T05:00:00.000Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"g104-test-address\"}",key(),"application/json","application/json");
        expect(r,201,null); long id=Long.parseLong(r.data().path("orderId").asString()); ids.add(id);
        assertThat(com.autocare.server.web.UtcTimes.format(db.queryForObject("SELECT accept_deadline FROM t_service_order WHERE id=?",LocalDateTime.class,id)))
                .isEqualTo(r.data().path("acceptDeadline").asString());
        return id;
    }

    private Reply command(long id,String action,long actor,String version,String key,String reason) throws Exception {
        String body="{\"expectedRowVersion\":\""+version+"\""+(reason==null?"":",\"reason\":"+json.writeValueAsString(reason))+"}";
        return send("POST","/"+id+"/"+action,actor,body,key,"application/json","application/json");
    }
    private Reply uncheckedCommand(long id,String action,long actor,String version,String key) {
        try { return command(id,action,actor,version,key,null); } catch(Exception e) { throw new RuntimeException(e); }
    }
    private Reply get(String suffix,long actor) throws Exception { return send("GET",suffix,actor,null,"read-key-does-nothing",null,"application/json"); }
    private Reply send(String method,String suffix,long actor,String body,String key,String contentType,String accept) throws Exception {
        Role role= actor<=2?Role.OWNER:actor<=4?Role.TECH:Role.ADMIN;
        return sendToken(method,suffix,issuer.issue(actor,role,Instant.now()),body,key,contentType,accept);
    }
    private Reply sendToken(String method,String suffix,String token,String body,String key,String contentType,String accept) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/orders"+suffix)).timeout(Duration.ofSeconds(15));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(key!=null)b.header("Idempotency-Key",key);
        if(contentType!=null)b.header("Content-Type",contentType);
        b.header("Accept",accept).header("X-Request-Id",UUID.randomUUID().toString());
        HttpResponse<String> r=client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        // 不输出 token；响应只含本片合成地址与脱敏车牌。
        System.out.println("G104 HTTP "+method+" "+suffix+" status="+r.statusCode()+" body="+ascii(r.body()));
        return new Reply(r,json.readTree(r.body()));
    }
    private void expect(Reply r,int status,String code) {
        assertThat(r.status()).describedAs(r.body.toString()).isEqualTo(status);
        if(code!=null)assertThat(r.body.path("error").path("code").asString()).isEqualTo(code);
    }
    private String key(){ String k=UUID.randomUUID().toString(); keys.add(k); return k; }
    private synchronized String keyThreadSafe(){ return key(); }
    private int keyRows(String k){return db.queryForObject("SELECT COUNT(*) FROM t_idempotency WHERE idempotency_key=?",Integer.class,k);}
    private void assertInvariant(long id,String state,int version,boolean active,int commands) {
        Map<String,Object> row=db.queryForMap("SELECT state,row_version,reservation_active,actual_start,actual_finish FROM t_service_order WHERE id=?",id);
        int logs=db.queryForObject("SELECT COUNT(*) FROM t_order_state_log WHERE order_id=?",Integer.class,id);
        int ops=db.queryForObject("SELECT COUNT(*) FROM t_order_operation WHERE order_id=?",Integer.class,id);
        // Numeric resource IDs may overlap across vehicle and order operations.
        int idem=db.queryForObject("SELECT COUNT(*) FROM t_idempotency WHERE resource_id=? "
                + "AND operation IN ('acceptOrder','declineOrder','cancelOrder','departOrder','arriveOrder')",Integer.class,id);
        System.out.println("G104 INVARIANT id="+id+" row="+row+" logs="+logs+" operations="+ops+" commandResults="+idem);
        assertThat(row.get("state")).isEqualTo(state);
        assertThat(((Number)row.get("row_version")).intValue()).isEqualTo(version);
        assertThat(row.get("reservation_active").toString()).isIn(active?new String[]{"true","1"}:new String[]{"false","0"});
        assertThat(row.get("actual_start")).isNull(); assertThat(row.get("actual_finish")).isNull();
        assertThat(logs).isEqualTo(version); assertThat(ops).isEqualTo(version-1); assertThat(idem).isEqualTo(commands);
    }
    private <T> List<T> race(Supplier<? extends T> a,Supplier<? extends T> b) throws Exception {
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures=new ArrayList<>();
            for(Supplier<? extends T> action:List.of(a,b)) futures.add(pool.submit(()->{ready.countDown(); if(!go.await(5,TimeUnit.SECONDS))throw new IllegalStateException("barrier"); return action.get();}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); go.countDown();
            List<T> result=new ArrayList<>(); for(Future<T> f:futures)result.add(f.get(20,TimeUnit.SECONDS));
            System.out.println("G104 RACE results="+result.stream().map(x->x instanceof Reply r?r.status()+":"+r.body.path("error").path("code").asString():String.valueOf(x)).toList());
            return result;
        }
    }
    private OrderVersionRequest version(int v) { OrderVersionRequest r=new OrderVersionRequest(); r.setExpectedRowVersion(json.readTree("\""+v+"\"")); return r; }
    private void installTech(){SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg","HS256").subject("3").build()));}
    private static String ascii(String raw){StringBuilder s=new StringBuilder(); for(char c:raw.toCharArray()){if(c>=32&&c<127)s.append(c);else s.append(String.format("\\u%04x",(int)c));}return s.toString();}
    private record Reply(HttpResponse<String> response,JsonNode body){ int status(){return response.statusCode();} JsonNode data(){return body.path("data");} }
}
