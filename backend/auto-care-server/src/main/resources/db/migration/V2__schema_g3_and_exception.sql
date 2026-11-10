-- =============================================================================
-- V2：G3 支撑 6 张 + 人工异常表（G0-03）
-- 权威来源：项目对话/02-开工准备/接口文档/12-数据库字段字典.md §4（逐表转写，不改语义）
-- 依赖：V1 的 t_user / t_service_order / t_order_quote 已存在。
-- 建表顺序：t_knowledge_head / t_background_job 先于 t_knowledge_document；
--           t_quote_analysis_result 与 t_ai_call_log 此时对 order/quote/user 的
--           FK 全部可建，不用留到后补。
-- =============================================================================

-- 1. t_background_job 持久化异步任务 ----------------------------------------
CREATE TABLE t_background_job (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  type                 ENUM('QUOTE_ANALYSIS','KNOWLEDGE_INDEX') NOT NULL,
  business_key         VARCHAR(200)    NOT NULL,
  actor_id             BIGINT UNSIGNED NOT NULL,
  resource_id          BIGINT UNSIGNED NULL,
  payload              JSON            NOT NULL,
  status               ENUM('PENDING','RUNNING','SUCCEEDED','RETRY_WAIT','FAILED')
                       NOT NULL DEFAULT 'PENDING',
  next_run_at          DATETIME(3)     NULL,
  lease_owner          VARCHAR(64)     NULL,
  claim_token          CHAR(36)        NULL,
  lease_until          DATETIME(3)     NULL,
  attempts             INT UNSIGNED    NOT NULL DEFAULT 0 CHECK (attempts <= 100),
  retry_generation     INT UNSIGNED    NOT NULL DEFAULT 1
                       CHECK (retry_generation BETWEEN 1 AND 3),
  last_error_code      VARCHAR(64)     NULL,
  last_error_message   VARCHAR(200)    NULL,
  last_error_retryable TINYINT(1)      NULL,
  created_at           DATETIME(3)     NOT NULL,
  updated_at           DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_bk (business_key),
  KEY idx_job_claim (status, next_run_at, id),
  CONSTRAINT fk_job_actor FOREIGN KEY (actor_id) REFERENCES t_user (id),
  CHECK (
    (last_error_code IS NULL AND last_error_message IS NULL AND last_error_retryable IS NULL)
    OR (last_error_code IS NOT NULL AND last_error_message IS NOT NULL
        AND last_error_retryable IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='持久化任务；领取/续租/完成全部走条件更新（行数=1）';

-- 2. t_knowledge_head 知识系列头（发布锁载体）--------------------------------
CREATE TABLE t_knowledge_head (
  document_key    VARCHAR(64)  NOT NULL,
  desired_version INT UNSIGNED NOT NULL DEFAULT 1
                  CHECK (desired_version BETWEEN 1 AND 2147483647),
  active_version  INT UNSIGNED NULL
                  CHECK (active_version IS NULL OR active_version BETWEEN 1 AND 2147483647),
  created_at      DATETIME(3)  NOT NULL,
  updated_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (document_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='documentKey 级头指针；发布顺序=锁 job(验 token/租约)→锁 head→校验 desired→更新 active，禁止反向嵌套';

-- 3. t_knowledge_document 知识内容版本（不可变）------------------------------
CREATE TABLE t_knowledge_document (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  document_key  VARCHAR(64)     NOT NULL,
  version       INT UNSIGNED    NOT NULL
                CHECK (version BETWEEN 1 AND 2147483647),
  title         VARCHAR(200)    NOT NULL,
  model_tags    JSON            NOT NULL COMMENT 'ModelTag 数组 1—20',
  source        VARCHAR(200)    NULL,
  content_type  ENUM('application/pdf','text/plain','text/markdown') NOT NULL,
  size_bytes    INT UNSIGNED    NOT NULL CHECK (size_bytes <= 10485760),
  file_path     VARCHAR(512)    NOT NULL COMMENT '服务端存储位置，禁止外露',
  page_count    INT UNSIGNED    NULL CHECK (page_count IS NULL OR page_count BETWEEN 1 AND 500),
  char_count    INT UNSIGNED    NULL CHECK (char_count IS NULL OR char_count <= 2000000),
  published     TINYINT(1)      NOT NULL DEFAULT 0,
  stale_reason  ENUM('SUPERSEDED','NO_LONGER_CURRENT','CONTENT_CHANGED','EVIDENCE_VERSION_CHANGED') NULL,
  latest_job_id BIGINT UNSIGNED NULL,
  created_at    DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_version (document_key, version),
  KEY idx_doc_job (latest_job_id),
  CONSTRAINT fk_doc_head FOREIGN KEY (document_key) REFERENCES t_knowledge_head (document_key),
  CONSTRAINT fk_doc_job  FOREIGN KEY (latest_job_id) REFERENCES t_background_job (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='一个 (documentKey,version) 一行；内容/来源/chunk 不覆盖';

-- 4. t_document_chunk 资料片段（引用校验底座）--------------------------------
CREATE TABLE t_document_chunk (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  document_id    BIGINT UNSIGNED NOT NULL,
  version        INT UNSIGNED    NOT NULL,
  chunk_key      VARCHAR(128)    NOT NULL,
  source_locator VARCHAR(200)    NOT NULL,
  text_hash      CHAR(64)        NOT NULL,
  text_content   MEDIUMTEXT      NOT NULL,
  created_at     DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk (document_id, chunk_key),
  CONSTRAINT fk_chunk_doc FOREIGN KEY (document_id) REFERENCES t_knowledge_document (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='稳定片段身份；引用校验=引用的 (documentKey,version,chunkKey) 必须属于本次授权候选';

-- 5. t_quote_analysis_result 报价分析结果（一次写入不可变）-------------------
CREATE TABLE t_quote_analysis_result (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  job_id        BIGINT UNSIGNED NOT NULL,
  quote_id      BIGINT UNSIGNED NOT NULL,
  quote_version INT UNSIGNED    NOT NULL
                CHECK (quote_version BETWEEN 1 AND 2147483647),
  content_hash  CHAR(64)        NOT NULL,
  findings      JSON            NOT NULL,
  missing_evidence JSON         NOT NULL,
  citations     JSON            NOT NULL,
  limitations   JSON            NOT NULL,
  analyzed_at   DATETIME(3)     NOT NULL,
  created_at    DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_analysis_job (job_id),
  KEY idx_analysis_quote (quote_id, quote_version),
  CONSTRAINT fk_ar_job   FOREIGN KEY (job_id)   REFERENCES t_background_job (id),
  CONSTRAINT fk_ar_quote FOREIGN KEY (quote_id) REFERENCES t_order_quote (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='分析结果不可变；stale 读时判定，不回写';

-- 6. t_ai_call_log 模型调用观察 ---------------------------------------------
CREATE TABLE t_ai_call_log (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scene              ENUM('BOOKING_DRAFT','QUOTE_ANALYSIS','HISTORY_QUESTION','KNOWLEDGE_INDEX') NOT NULL,
  actor_id           BIGINT UNSIGNED NOT NULL,
  order_id           BIGINT UNSIGNED NULL,
  quote_id           BIGINT UNSIGNED NULL,
  input_hash         CHAR(64)        NOT NULL,
  prompt_version     VARCHAR(64)     NOT NULL,
  model_id           VARCHAR(64)     NOT NULL,
  document_versions  JSON            NOT NULL,
  outcome            ENUM('SUCCEEDED','FAILED','TIMEOUT','VALIDATION_FAILED') NOT NULL,
  latency_ms         INT UNSIGNED    NULL CHECK (latency_ms IS NULL OR latency_ms <= 600000),
  prompt_tokens      INT UNSIGNED    NULL,
  completion_tokens  INT UNSIGNED    NULL,
  total_tokens       INT UNSIGNED    NULL,
  estimated_cost_cny DECIMAL(11,4)   NULL CHECK (estimated_cost_cny IS NULL OR estimated_cost_cny >= 0),
  error_code         VARCHAR(64)     NULL,
  created_at         DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_scene_created (scene, created_at, id),
  KEY idx_ai_created (created_at, id),
  KEY idx_ai_actor (actor_id),
  KEY idx_ai_order (order_id),
  KEY idx_ai_quote (quote_id),
  CONSTRAINT fk_ai_actor  FOREIGN KEY (actor_id) REFERENCES t_user (id),
  CONSTRAINT fk_ai_order  FOREIGN KEY (order_id) REFERENCES t_service_order (id),
  CONSTRAINT fk_ai_quote  FOREIGN KEY (quote_id) REFERENCES t_order_quote (id),
  CHECK (
    (prompt_tokens IS NULL AND completion_tokens IS NULL AND total_tokens IS NULL)
    OR (prompt_tokens IS NOT NULL AND completion_tokens IS NOT NULL AND total_tokens IS NOT NULL)
  ),
  CHECK (prompt_tokens IS NULL OR prompt_tokens <= 1000000),
  CHECK (completion_tokens IS NULL OR completion_tokens <= 1000000),
  CHECK (total_tokens IS NULL OR total_tokens <= 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='脱敏元数据；不含完整提示词/问题原文/密钥；order_id 循环 FK 见 §7.2';

-- 7. t_order_exception 人工异常（独立表，ACCEPT-004 Q-03）--------------------
CREATE TABLE t_order_exception (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  type                 ENUM('DEFERRAL_SLOT_CONFLICT','OVER_PLAN_COMPLETION') NOT NULL,
  order_id             BIGINT UNSIGNED NOT NULL,
  request_id           CHAR(36)        NOT NULL,
  requested_target_end DATETIME(3)     NULL,
  committed_end        DATETIME(3)     NULL,
  actual_finish        DATETIME(3)     NULL,
  over_plan_minutes    SMALLINT UNSIGNED NULL
                       CHECK (over_plan_minutes IS NULL OR over_plan_minutes BETWEEN 1 AND 10080),
  recorded_at          DATETIME(3)     NOT NULL,
  resolved             TINYINT(1)      NOT NULL DEFAULT 0,
  created_at           DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exception_dedup (order_id, request_id, type),
  CONSTRAINT fk_exception_order FOREIGN KEY (order_id) REFERENCES t_service_order (id),
  CHECK (
    (type = 'DEFERRAL_SLOT_CONFLICT'
     AND requested_target_end IS NOT NULL AND committed_end IS NULL
     AND actual_finish IS NULL AND over_plan_minutes IS NULL)
    OR
    (type = 'OVER_PLAN_COMPLETION'
     AND requested_target_end IS NULL AND committed_end IS NOT NULL
     AND actual_finish IS NOT NULL AND over_plan_minutes IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='人工异常独立表；去重 orderId+requestId+type；无自由文本';
