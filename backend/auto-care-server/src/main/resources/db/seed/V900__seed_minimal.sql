-- =============================================================================
-- V900：最小种子（G0-03）
-- 权威依据：
--   项目对话/02-开工准备/02-核心验收与数据准备.md「种子数据」段
--   设计 v1.1 §3.1（服务项目与演示参数、通勤 30 分钟）
--   项目对话/02-开工准备/接口文档/12-数据库字段字典.md §7.3
--
-- 范围：只造 G0-04 身份接口与 G1 路径跑得起来的最小集。
--       订单 / 报价 / 流水 / 操作 / 幂等 / 任务 / 知识 / AI 日志 **一律不种**。
--
-- 三处 G0-03 已拍板的口径（字典留给我拍板，理由写在回报里）：
--   1. 窗口按「天」一行，不是 §7.3 字面的「各一个」：设计 §3.1 原话是「技师提供
--      具体日期的工作窗口」；连续 7 天单窗口等于 24 小时可用，窗口校验形同虚设。
--   2. BRAKE_PAD 以 enabled=0 入种：G1 要验「停用目录项不进新意向」，需要现成停用样本。
--   3. ID 全部纯自增，不显式指 ID：字典 §6.3 明确 doc 样例编号是演示值、不构成分配规则；
--      种子行用 login_name / code 唯一键定位。
--
-- 密码：**仓库里只有占位符 ${seedPasswordHash}**。
--   真实哈希从环境变量 SEED_PASSWORD_HASH 注入（application-example.yml →
--   spring.flyway.placeholders.seedPasswordHash）。空串等于没有哈希，
--   驱动脚本会先断言非空再跑迁移。
-- =============================================================================

-- 1. 账号：两个车主 / 两个技师 / 一个管理员 ----------------------------------
INSERT INTO t_user (login_name, password_hash, role, enabled, created_at, updated_at) VALUES
  ('owner1', '${seedPasswordHash}', 'OWNER', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('owner2', '${seedPasswordHash}', 'OWNER', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('tech1',  '${seedPasswordHash}', 'TECH',  1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('tech2',  '${seedPasswordHash}', 'TECH',  1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('admin1', '${seedPasswordHash}', 'ADMIN', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 2. 技师资源行：commute_minutes=30 是设计 §3.1 的默认固定通勤 --------------
INSERT INTO t_technician (user_id, display_name, enabled, commute_minutes, schedule_version,
                          created_at, updated_at) VALUES
  ((SELECT id FROM t_user WHERE login_name = 'tech1'), '技师 张', 1, 30, 1,
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ((SELECT id FROM t_user WHERE login_name = 'tech2'), '技师 李', 1, 30, 1,
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 3. 工作窗口：运行日「次日起 7 天」，每天 Asia/Shanghai 09:00–18:00 → UTC 01:00–10:00
--    用相对偏移，不硬编码已过去日期（02-核心验收与数据准备.md 的硬要求）。
--    两技师窗口完全重合 → P03「T1/T2 同时段预约可各自成功」天然可用。
INSERT INTO t_technician_window (technician_id, window_start, window_end, created_at, updated_at)
SELECT tech.user_id,
       DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL d.n DAY)) + INTERVAL 1  HOUR,
       DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL d.n DAY)) + INTERVAL 10 HOUR,
       UTC_TIMESTAMP(3),
       UTC_TIMESTAMP(3)
FROM t_technician AS tech
CROSS JOIN (
  SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
) AS d;

-- 4. 车辆：每个车主 1 辆，归属明确。
--    plate_mask_source 是演示车牌；脱敏展示格式（G0-04 拍板）在此只是占位样本。
INSERT INTO t_vehicle (owner_id, model_code, plate_mask_source, plate_masked, mileage,
                       row_version, created_at, updated_at) VALUES
  ((SELECT id FROM t_user WHERE login_name = 'owner1'), 'COROLLA_2020', '沪A12345', '沪A***45',
   35000, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ((SELECT id FROM t_user WHERE login_name = 'owner2'), 'CAMRY_2021',   '沪B67890', '沪B***90',
   60000, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 5. 服务目录：设计 §3.1 四项 + BRAKE_PAD(停用)。价格/工时都是演示值，不是市场报价。
--    版本固定 1（catalogVersion），停用只影响新意向，历史批准行走快照。
INSERT INTO t_service_item (code, name, duration_minutes, price, currency, enabled, version,
                            created_at, updated_at) VALUES
  ('OIL_FILTER',   '机油机滤更换',   45, 199.00, 'CNY', 1, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('AIR_FILTER',   '空气滤芯更换',   20,  89.00, 'CNY', 1, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('CABIN_FILTER', '空调滤芯更换',   20, 119.00, 'CNY', 1, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('BATTERY_TEST', '电瓶检测与更换', 30, 159.00, 'CNY', 1, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('BRAKE_PAD',    '刹车片更换',     60, 399.00, 'CNY', 0, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
