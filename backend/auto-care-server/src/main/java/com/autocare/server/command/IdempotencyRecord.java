package com.autocare.server.command;

import java.time.LocalDateTime;

/**
 * {@code t_idempotency} 一行的 Java 投影。
 *
 * <p>字段名 camelCase ↔ 列名 snake_case，靠 MyBatis 的 map-underscore-to-camel-case 对上
 * （和 {@code UserEntity} 同一套规矩，别把列名直接搬进 Java）。
 *
 * <p><b>这一行有两种"半成品"状态，只有一种能提交出门。</b>看 {@link #responseCode}：
 * <ul>
 *   <li><b>占位态</b>——刚插进去、业务动作还没跑。{@code responseCode = 0}、
 *       {@code responseJson = "{}"}。它活在事务里，外面看不见。</li>
 *   <li><b>完成态</b>——业务动作跑完、响应回填好，和业务写入一起提交。
 *       这才是能被重放的那一行。</li>
 * </ul>
 * 占位态之所以成立，靠的是事务隔离：别的连接读不到我未提交的行。
 * 反过来说——**如果哪天真查到一行 {@code responseCode = 0}，那是个 bug**：
 * 说明我们让一个半成品提交出去了。这条留在这儿，将来查故障时可以直接拿来用。
 */
public class IdempotencyRecord {

    /** 占位行的 response_code 哨兵值。真响应最小是 200，撞不上。 */
    public static final int PLACEHOLDER_RESPONSE_CODE = 0;

    /** 占位行的 response_json。列是 NOT NULL，所以必须先给个合法的空 JSON 顶上。 */
    public static final String PLACEHOLDER_RESPONSE_JSON = "{}";

    private Long id;
    private Long actorId;
    private String operation;
    private String idempotencyKey;
    private String requestHash;
    private Integer responseCode;
    private String responseLocation;

    /**
     * <b>原样存整个响应信封</b>——不是只存 {@code data}。
     *
     * <p>为什么？因为重放要回两个东西：原 {@code data}，以及响应头 {@code Original-Request-Id}。
     * 后者就是<b>原请求的 requestId</b>。契约说「原 response 含原 requestId 用于审计，
     * 传输时替换为本次有效 requestId」——把整个信封存下来，这两件一次都拿到了；
     * 只存 data 的话，原 requestId 就永远丢了。
     */
    private String responseJson;

    /** 业务主键（如新建的 vehicleId）。契约要求「保留期间不能丢 resource_id/response」。 */
    private Long resourceId;

    /** 保留到期时间。NULL = 订单未终态，不得清理。本片只写值，不做清理任务。 */
    private LocalDateTime keepUntil;

    private LocalDateTime createdAt;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(Long actorId, String operation, String idempotencyKey, String requestHash) {
        this.actorId = actorId;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(Integer responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseLocation() {
        return responseLocation;
    }

    public void setResponseLocation(String responseLocation) {
        this.responseLocation = responseLocation;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public LocalDateTime getKeepUntil() {
        return keepUntil;
    }

    public void setKeepUntil(LocalDateTime keepUntil) {
        this.keepUntil = keepUntil;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
