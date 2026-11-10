-- G1-04：接在已部署 V901 之后，自动迁移也要满足流水/操作 actor_id 的非空外键。
-- 专用停用账号仅标识系统操作，没有可用密码，不参与任何登录/授权旁路。
INSERT INTO t_user (login_name, password_hash, role, enabled, created_at, updated_at)
VALUES ('system_accept_expiry', '{disabled}', 'ADMIN', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
