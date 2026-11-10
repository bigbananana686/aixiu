-- =============================================================================
-- V901：刷新技师工作窗口（种子时效维护，不是业务功能）
--
-- 为什么需要这个文件：
--   V900 的窗口是「运行日『次日起 7 天』」相对生成的（见 V900 第 3 段）。这是刻意
--   不硬编码日期的做法，代价是**它会随真实时间流逝而整段过期**。G0-03 于 2026-10-22
--   跑出窗口区间 2026-10-23—2026-10-29；到 2026-10-31 已经全部是过去时间。
--   车辆片（G1-02a）不读窗口所以没事，但 G1-02b 的 getTechnicianAvailability 与
--   G1-03 建预约都要拿它做判断，过期数据等于没有数据。
--
-- 为什么用迁移而不是手动 UPDATE：
--   `spring.sql.init.mode: never`，结构与种子只有 Flyway 一条路；手动 UPDATE 在
--   「空库重建」时会凭空消失，下次重建又回到过期状态。写成 V901 才可追溯、可重建。
--
-- 为什么**不能**重放 V900：
--   Flyway 的校验和锚定的是文件字节，删了记录重跑 V900 会让既有库的校验失败。
--   刷新一律新增版本。
--
-- 换窗口要不要顶 schedule_version：
--   要。t_technician 的列注释是「一切占用/窗口变更先锁本行」，窗口数据变了而版本不动，
--   读方就分不出「排期变了」和「排期没变」。所以本文件同时把两个种子技师的
--   schedule_version 各 +1（从 1 到 2）。这条只对**种子维护**成立；将来 G2-01b 的
--   replaceTechnicianWindows 是业务路径，走它自己的事务与锁。
--
-- 范围：只动 tech1／tech2 两个种子技师，不碰任何别的行。
-- 时效：与 V900 同形——次日起 7 天、每天 UTC 01:00—10:00（Asia/Shanghai 09:00—18:00）。
--       也就是说**它同样会在大约 7 天后过期**；届时照本文件复制一个 V902 即可（只改注释）。
-- =============================================================================

DELETE w
FROM t_technician_window AS w
JOIN t_user AS u ON u.id = w.technician_id
WHERE u.login_name IN ('tech1', 'tech2');

INSERT INTO t_technician_window (technician_id, window_start, window_end, created_at, updated_at)
SELECT tech.user_id,
       DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL d.n DAY)) + INTERVAL 1  HOUR,
       DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL d.n DAY)) + INTERVAL 10 HOUR,
       UTC_TIMESTAMP(3),
       UTC_TIMESTAMP(3)
FROM t_technician AS tech
JOIN t_user      AS u ON u.id = tech.user_id
CROSS JOIN (
  SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
) AS d
WHERE u.login_name IN ('tech1', 'tech2');

UPDATE t_technician
SET schedule_version = schedule_version + 1,
    updated_at       = UTC_TIMESTAMP(3)
WHERE user_id IN (SELECT id FROM t_user WHERE login_name IN ('tech1', 'tech2'));
