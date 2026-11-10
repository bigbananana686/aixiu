-- 用户授权技师撤回自己的当前待确认报价；追加状态，保留历史迁移校验和。
ALTER TABLE t_order_quote MODIFY COLUMN status ENUM('PENDING','CONFIRMED','REJECTED','EXPIRED','SUPERSEDED','WITHDRAWN') NOT NULL;
