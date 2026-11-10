-- D5追加迁移：保留V1—V903。正式库本轮不迁移。
-- 文件索引在锁外完成；本行只在fenced结果事务中发布完整索引路径。
ALTER TABLE t_knowledge_document
  ADD COLUMN index_path VARCHAR(512) NULL,
  ADD COLUMN index_space CHAR(64) NULL,
  ADD COLUMN embedding_model VARCHAR(64) NULL,
  ADD COLUMN embedding_dimensions INT UNSIGNED NULL CHECK (embedding_dimensions BETWEEN 1 AND 4096),
  ADD COLUMN chunk_strategy VARCHAR(64) NULL,
  ADD COLUMN parsed_page_count INT UNSIGNED NULL,
  ADD COLUMN parsed_char_count INT UNSIGNED NULL;

-- 超限解析的实际计数独立保存，避免破坏page_count<=500 / char_count<=2000000的契约。
ALTER TABLE t_quote_analysis_result ADD COLUMN document_versions JSON NULL;

CREATE TABLE t_ai_admission (
  id TINYINT UNSIGNED NOT NULL PRIMARY KEY CHECK (id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO t_ai_admission(id) VALUES(1);
