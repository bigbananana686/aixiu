-- =============================================================================
-- V1：G1 核心 11 张表（G0-03）
-- 权威来源：项目对话/02-开工准备/接口文档/12-数据库字段字典.md §3（逐表转写，不改语义）
-- 建表顺序按字典 §7.1：被引用的表先建。
--
-- 本文件刻意只建「无循环依赖」的表和 FK：
--   t_service_order.pending_quote_id / approved_quote_id / ai_parse_id 三列先建普通 KEY、
--   不带 FK，三条循环外键在 V3 用 ALTER TABLE 后补（字典 §7.2；
--   G0-G1 任务清单明确允许，但「不许为建表顺序悄悄取消归属检查」——所以后补这件事
--   必须是版本历史里看得见的一步，不能靠省略建完）。
--
-- 硬约束（G0-01 实测，容器 lower_case_table_names=0）：表名/列名一律小写。
-- 时间列一律 DATETIME(3) 存 UTC；created_at/updated_at 由应用在事务内写，
-- 不用 CURRENT_TIMESTAMP 默认值（字典 §1.2，保证与业务写入同源、可测）。
-- =============================================================================

-- 1. t_user 账号 -------------------------------------------------------------
CREATE TABLE t_user (
  id            BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
  login_name    VARCHAR(64)         NOT NULL COMMENT '规范化小写登录名',
  password_hash VARCHAR(255)        NOT NULL COMMENT '只存强哈希',
  role          ENUM('OWNER','TECH','ADMIN') NOT NULL,
  enabled       TINYINT(1)          NOT NULL DEFAULT 1,
  created_at    DATETIME(3)         NOT NULL,
  updated_at    DATETIME(3)         NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_login (login_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号表；注册只产 OWNER';

-- 2. t_technician 技师（guard 锁载体）---------------------------------------
CREATE TABLE t_technician (
  user_id          BIGINT UNSIGNED  NOT NULL,
  display_name     VARCHAR(64)      NOT NULL COMMENT '对外显示名',
  enabled          TINYINT(1)       NOT NULL DEFAULT 1,
  commute_minutes  SMALLINT UNSIGNED NOT NULL DEFAULT 30
                   CHECK (commute_minutes BETWEEN 0 AND 480),
  schedule_version INT UNSIGNED     NOT NULL DEFAULT 1
                   CHECK (schedule_version BETWEEN 1 AND 2147483647),
  created_at       DATETIME(3)      NOT NULL,
  updated_at       DATETIME(3)      NOT NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_tech_user FOREIGN KEY (user_id) REFERENCES t_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='技师资源行；一切占用/窗口变更先锁本行（设计 §7.1）';

-- 3. t_technician_window 工作窗口 -------------------------------------------
CREATE TABLE t_technician_window (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  technician_id  BIGINT UNSIGNED NOT NULL,
  window_start   DATETIME(3)     NOT NULL,
  window_end     DATETIME(3)     NOT NULL,
  created_at     DATETIME(3)     NOT NULL,
  updated_at     DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_window_lookup (technician_id, window_start),
  CONSTRAINT fk_window_tech FOREIGN KEY (technician_id) REFERENCES t_technician (user_id),
  CHECK (window_start < window_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='按日期范围整体替换；替换须持技师资源行锁';

-- 4. t_vehicle 车辆 ----------------------------------------------------------
CREATE TABLE t_vehicle (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  owner_id          BIGINT UNSIGNED NOT NULL,
  model_code        VARCHAR(64)     NOT NULL,
  plate_mask_source VARCHAR(16)     NULL COMMENT '原始车牌，仅用于生成脱敏值，禁止外露',
  plate_masked      VARCHAR(32)     NOT NULL COMMENT '脱敏车牌',
  mileage           INT UNSIGNED    NOT NULL CHECK (mileage <= 9999999),
  row_version       INT UNSIGNED    NOT NULL DEFAULT 1
                    CHECK (row_version BETWEEN 1 AND 2147483647),
  created_at        DATETIME(3)     NOT NULL,
  updated_at        DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_vehicle_owner (owner_id, id),
  CONSTRAINT fk_vehicle_owner FOREIGN KEY (owner_id) REFERENCES t_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='车辆档案；历史订单用快照列，不随本表修改漂移';

-- 5. t_service_item 服务目录 -------------------------------------------------
CREATE TABLE t_service_item (
  id               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  code             VARCHAR(32)      NOT NULL,
  name             VARCHAR(64)      NOT NULL,
  duration_minutes SMALLINT UNSIGNED NOT NULL
                   CHECK (duration_minutes BETWEEN 1 AND 1440),
  price            DECIMAL(7,2)     NOT NULL CHECK (price >= 0 AND price <= 99999.99),
  currency         CHAR(3)          NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
  enabled          TINYINT(1)       NOT NULL DEFAULT 1,
  version          INT UNSIGNED     NOT NULL DEFAULT 1
                   CHECK (version BETWEEN 1 AND 2147483647),
  created_at       DATETIME(3)      NOT NULL,
  updated_at       DATETIME(3)      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_item_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务目录；目录写入只按目录行顺序加锁，不反向取技师/订单锁';

-- 6. t_service_order 预约/工单主表 -------------------------------------------
-- 注意：pending_quote_id / approved_quote_id / ai_parse_id 只有 KEY，没有 FK（V3 后补）
CREATE TABLE t_service_order (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no          VARCHAR(32)     NOT NULL,
  owner_id          BIGINT UNSIGNED NOT NULL,
  vehicle_id        BIGINT UNSIGNED NOT NULL,
  technician_id     BIGINT UNSIGNED NOT NULL COMMENT '创建后不可变',
  state             ENUM('RESERVED','ACCEPTED','EN_ROUTE','ARRIVED','QUOTE_PENDING',
                         'READY_FOR_SERVICE','SERVICING','COMPLETED','CLOSED','CANCELLED')
                    NOT NULL,
  row_version       INT UNSIGNED    NOT NULL DEFAULT 1
                    CHECK (row_version BETWEEN 1 AND 2147483647),
  service_start     DATETIME(3)     NOT NULL,
  occupied_start    DATETIME(3)     NOT NULL,
  committed_end     DATETIME(3)     NOT NULL,
  effective_end     DATETIME(3)     NOT NULL,
  reservation_active TINYINT(1)     NOT NULL DEFAULT 1,
  pending_quote_id  BIGINT UNSIGNED NULL,
  approved_quote_id BIGINT UNSIGNED NULL,
  quote_seq         INT UNSIGNED    NOT NULL DEFAULT 0 CHECK (quote_seq >= 0),
  accept_deadline   DATETIME(3)     NOT NULL,
  booking_items_json JSON           NOT NULL COMMENT 'BookingItemSnapshot 数组 1—50 行',
  rules_snapshot_json JSON          NOT NULL COMMENT 'RulesSnapshot 快照',
  raw_input         VARCHAR(2000)   NULL COMMENT '用户原始描述，不回显',
  address_snapshot  VARCHAR(500)    NOT NULL,
  mileage_snapshot  INT UNSIGNED    NOT NULL CHECK (mileage_snapshot <= 9999999),
  model_snapshot    VARCHAR(64)     NOT NULL,
  plate_snapshot    VARCHAR(32)     NOT NULL COMMENT '脱敏车牌快照',
  ai_parse_id       BIGINT UNSIGNED NULL,
  actual_start      DATETIME(3)     NULL,
  actual_finish     DATETIME(3)     NULL,
  service_result    VARCHAR(2000)   NULL,
  created_at        DATETIME(3)     NOT NULL,
  updated_at        DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_order_conflict (technician_id, reservation_active, occupied_start),
  KEY idx_order_owner_list (owner_id, created_at, id),
  KEY idx_order_tech_list (technician_id, created_at, id),
  KEY idx_order_accept_scan (state, accept_deadline),
  KEY idx_order_vehicle (vehicle_id),
  KEY idx_order_pending (pending_quote_id),
  KEY idx_order_approved (approved_quote_id),
  KEY idx_order_ai_parse (ai_parse_id),
  CONSTRAINT fk_order_owner      FOREIGN KEY (owner_id)     REFERENCES t_user (id),
  CONSTRAINT fk_order_vehicle    FOREIGN KEY (vehicle_id)   REFERENCES t_vehicle (id),
  CONSTRAINT fk_order_technician FOREIGN KEY (technician_id) REFERENCES t_technician (user_id),
  CHECK (occupied_start < committed_end),
  CHECK (effective_end >= committed_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='预约/工单；冲突检查走 (technician_id,reservation_active,occupied_start) 候选+锁定读';

-- 7. t_order_quote 报价头 ----------------------------------------------------
-- base_approved_quote_id 自引用与 order_id 引用建表时可建（被引用表先建）
CREATE TABLE t_order_quote (
  id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id               BIGINT UNSIGNED NOT NULL,
  version                INT UNSIGNED    NOT NULL
                         CHECK (version BETWEEN 1 AND 2147483647),
  purpose                ENUM('INITIAL','EXTRA') NOT NULL,
  base_approved_quote_id BIGINT UNSIGNED NULL,
  status                 ENUM('PENDING','CONFIRMED','REJECTED','EXPIRED','SUPERSEDED') NOT NULL,
  currency               CHAR(3)         NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
  total_amount           DECIMAL(9,2)    NOT NULL
                         CHECK (total_amount >= 0 AND total_amount <= 9999999.99),
  total_minutes          SMALLINT UNSIGNED NOT NULL
                         CHECK (total_minutes BETWEEN 1 AND 10080),
  added_minutes          SMALLINT UNSIGNED NOT NULL DEFAULT 0
                         CHECK (added_minutes BETWEEN 0 AND 10080),
  target_end             DATETIME(3)     NOT NULL,
  expires_at             DATETIME(3)     NOT NULL,
  submitted_by           BIGINT UNSIGNED NOT NULL,
  submitted_at           DATETIME(3)     NOT NULL,
  confirmed_by           BIGINT UNSIGNED NULL,
  confirmed_at           DATETIME(3)     NULL,
  terminal_reason        VARCHAR(500)    NULL,
  content_hash           CHAR(64)        NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_order_version (order_id, version),
  KEY idx_quote_expiry_scan (status, expires_at, id),
  KEY idx_quote_base (base_approved_quote_id),
  KEY idx_quote_submitted_by (submitted_by),
  KEY idx_quote_confirmed_by (confirmed_by),
  CONSTRAINT fk_quote_order      FOREIGN KEY (order_id)               REFERENCES t_service_order (id),
  CONSTRAINT fk_quote_base       FOREIGN KEY (base_approved_quote_id) REFERENCES t_order_quote (id),
  CONSTRAINT fk_quote_submitter  FOREIGN KEY (submitted_by)           REFERENCES t_user (id),
  CONSTRAINT fk_quote_confirmer  FOREIGN KEY (confirmed_by)           REFERENCES t_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='报价头；内容/条目/contentHash/targetEnd 发布后不可覆盖';

-- 8. t_order_quote_item 报价条目（完整快照行）--------------------------------
CREATE TABLE t_order_quote_item (
  id               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  quote_id         BIGINT UNSIGNED  NOT NULL,
  line_key         VARCHAR(64)      NOT NULL,
  source_item_id   BIGINT UNSIGNED  NOT NULL,
  name_snapshot    VARCHAR(64)      NOT NULL,
  unit_price       DECIMAL(7,2)     NOT NULL CHECK (unit_price >= 0 AND unit_price <= 99999.99),
  currency         CHAR(3)          NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
  quantity         TINYINT UNSIGNED NOT NULL CHECK (quantity BETWEEN 1 AND 99),
  duration_minutes SMALLINT UNSIGNED NOT NULL
                   CHECK (duration_minutes BETWEEN 1 AND 1440),
  is_extra         TINYINT(1)       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_item (quote_id, line_key),
  KEY idx_qi_source_item (source_item_id),
  CONSTRAINT fk_qi_quote FOREIGN KEY (quote_id)       REFERENCES t_order_quote (id),
  CONSTRAINT fk_qi_item  FOREIGN KEY (source_item_id) REFERENCES t_service_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='报价条目快照；EXTRA 承袭行由服务端逐行核对旧 lineKey 完整保留';

-- 9. t_order_state_log 状态流水（只追加）-------------------------------------
CREATE TABLE t_order_state_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id    BIGINT UNSIGNED NOT NULL,
  from_state  ENUM('RESERVED','ACCEPTED','EN_ROUTE','ARRIVED','QUOTE_PENDING',
                   'READY_FOR_SERVICE','SERVICING','COMPLETED','CLOSED','CANCELLED') NULL,
  to_state    ENUM('RESERVED','ACCEPTED','EN_ROUTE','ARRIVED','QUOTE_PENDING',
                   'READY_FOR_SERVICE','SERVICING','COMPLETED','CLOSED','CANCELLED') NOT NULL,
  action      VARCHAR(48)     NOT NULL,
  actor_id    BIGINT UNSIGNED NOT NULL,
  request_id  CHAR(36)        NOT NULL,
  created_at  DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_state_log_order (order_id, id),
  CONSTRAINT fk_slog_order FOREIGN KEY (order_id) REFERENCES t_service_order (id),
  CONSTRAINT fk_slog_actor FOREIGN KEY (actor_id) REFERENCES t_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='只记已提交的真实迁移；禁 UPDATE/DELETE；替换报价/改期/延期不产生流水';

-- 10. t_order_operation 操作记录（只追加）------------------------------------
CREATE TABLE t_order_operation (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id        BIGINT UNSIGNED NOT NULL,
  action          VARCHAR(48)     NOT NULL,
  quote_id        BIGINT UNSIGNED NULL,
  actor_id        BIGINT UNSIGNED NOT NULL,
  payload_summary VARCHAR(500)    NULL COMMENT '脱敏摘要；不含密码/令牌/完整地址',
  request_id      CHAR(36)        NOT NULL,
  created_at      DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_op_order (order_id, id),
  KEY idx_op_quote (quote_id),
  CONSTRAINT fk_op_order  FOREIGN KEY (order_id) REFERENCES t_service_order (id),
  CONSTRAINT fk_op_quote  FOREIGN KEY (quote_id) REFERENCES t_order_quote (id),
  CONSTRAINT fk_op_actor  FOREIGN KEY (actor_id) REFERENCES t_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='只记真实提交的操作；成功重放不新增一条';

-- 11. t_idempotency 幂等（成功重放）------------------------------------------
CREATE TABLE t_idempotency (
  id                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  actor_id          BIGINT UNSIGNED  NOT NULL,
  operation         VARCHAR(64)      NOT NULL,
  idempotency_key   VARCHAR(128)     NOT NULL,
  request_hash      CHAR(64)         NOT NULL,
  response_code     SMALLINT UNSIGNED NOT NULL,
  response_location VARCHAR(255)     NULL,
  response_json     JSON             NOT NULL,
  resource_id       BIGINT UNSIGNED  NULL,
  keep_until        DATETIME(3)      NULL COMMENT 'NULL=订单未终态不得清理',
  created_at        DATETIME(3)      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem_scope (actor_id, operation, idempotency_key),
  KEY idx_idem_cleanup (keep_until),
  CONSTRAINT fk_idem_actor FOREIGN KEY (actor_id) REFERENCES t_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='幂等成功记录；与业务写入同事务提交；失败一起回滚';
