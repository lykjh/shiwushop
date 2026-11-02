ALTER TABLE addresses
    ADD COLUMN last_used_at DATETIME NULL COMMENT '最近一次成功创建订单时使用该地址的时间';

UPDATE addresses a
JOIN (
    SELECT user_id, MAX(id) AS id
    FROM addresses
    WHERE deleted_at IS NULL
    GROUP BY user_id
) newest ON newest.id = a.id
LEFT JOIN addresses existing
    ON existing.user_id = a.user_id AND existing.deleted_at IS NULL AND existing.is_default = 1
SET a.is_default = 1
WHERE existing.id IS NULL;
