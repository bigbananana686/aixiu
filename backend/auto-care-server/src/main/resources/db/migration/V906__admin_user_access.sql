ALTER TABLE t_user
  ADD COLUMN admin_version INT NOT NULL DEFAULT 1 CHECK (admin_version > 0);

CREATE TABLE t_user_access_change (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  previous_role ENUM('OWNER','TECH') NOT NULL,
  new_role ENUM('OWNER','TECH') NOT NULL,
  previous_enabled TINYINT(1) NOT NULL,
  new_enabled TINYINT(1) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_user_access_change_user (user_id, id),
  CONSTRAINT fk_user_access_change_actor FOREIGN KEY (actor_id) REFERENCES t_user(id),
  CONSTRAINT fk_user_access_change_target FOREIGN KEY (user_id) REFERENCES t_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
