-- 现有并发测试需要读取锁等待；仅供本地独立测试 MySQL 使用。
GRANT SELECT ON performance_schema.data_lock_waits TO 'shiwushop'@'%';
GRANT SELECT ON performance_schema.data_locks TO 'shiwushop'@'%';
GRANT SELECT ON performance_schema.threads TO 'shiwushop'@'%';
