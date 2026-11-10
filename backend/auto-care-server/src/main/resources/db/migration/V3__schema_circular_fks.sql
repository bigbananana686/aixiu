-- =============================================================================
-- V3：循环外键后补（G0-03）
-- 权威来源：项目对话/02-开工准备/接口文档/12-数据库字段字典.md §7.2（逐条转写）
--
-- 为什么单独一个版本文件：
--   t_service_order ↔ t_order_quote、t_service_order ↔ t_ai_call_log 是两组循环引用，
--   谁都引用谁，没法在一张 CREATE TABLE 里把两个方向都写全。
--   G0-G1 任务清单的处理方式是「双方表建好后再加」——但**不许**用建表顺序问题
--   默默取消归属检查。所以这一步必须是版本历史里独立可见的一行。
--
-- 完成条件（本片自证，证据落 SHOW CREATE TABLE）：
--   三条约束加完后必须真的生效，不能只看这条 ALTER 有没有报错。
-- =============================================================================

ALTER TABLE t_service_order ADD CONSTRAINT fk_order_pending
  FOREIGN KEY (pending_quote_id)  REFERENCES t_order_quote (id);

ALTER TABLE t_service_order ADD CONSTRAINT fk_order_approved
  FOREIGN KEY (approved_quote_id) REFERENCES t_order_quote (id);

ALTER TABLE t_service_order ADD CONSTRAINT fk_order_ai_parse
  FOREIGN KEY (ai_parse_id)       REFERENCES t_ai_call_log (id);
