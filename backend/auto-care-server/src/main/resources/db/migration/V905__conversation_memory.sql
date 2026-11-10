-- Spring AI官方MySQL ChatMemory结构，表名适配本项目全小写约束。
CREATE TABLE spring_ai_chat_memory (
  conversation_id VARCHAR(36) NOT NULL,
  content TEXT NOT NULL,
  type ENUM('USER','ASSISTANT','SYSTEM','TOOL') NOT NULL,
  timestamp TIMESTAMP NOT NULL,
  sequence_id BIGINT NOT NULL,
  KEY idx_memory_sequence(conversation_id,sequence_id),
  KEY idx_memory_timestamp(conversation_id,timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_ai_conversation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(36) NOT NULL UNIQUE,
  actor_id BIGINT UNSIGNED NOT NULL,
  facts JSON NOT NULL,
  revision INT UNSIGNED NOT NULL DEFAULT 0,
  claim_token CHAR(36) NULL,
  lease_until DATETIME(3) NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_conversation_actor(actor_id,deleted,updated_at,id),
  CONSTRAINT fk_conversation_actor FOREIGN KEY(actor_id) REFERENCES t_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_ai_conversation_turn (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT UNSIGNED NOT NULL,
  request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  request_hash CHAR(64) NOT NULL,
  message_text TEXT NOT NULL,
  status ENUM('RUNNING','SUCCEEDED','FAILED') NOT NULL,
  attempts INT UNSIGNED NOT NULL DEFAULT 1,
  response_json JSON NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_turn_request(conversation_id,request_key),
  KEY idx_turn_quota(created_at,conversation_id),
  CONSTRAINT fk_turn_conversation FOREIGN KEY(conversation_id) REFERENCES t_ai_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
