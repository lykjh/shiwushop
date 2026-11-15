-- 旧版取消只关闭砍价单，恢复确实没有其他砍价占用的账号额度。
UPDATE marketing_user_quotas q
SET reserved_count = 0, version = version + 1
WHERE q.quota_kind = 'OPEN_BARGAIN' AND q.period_key = 'ALL' AND q.reserved_count = 1
  AND EXISTS (
    SELECT 1 FROM marketing_bargains b
    JOIN marketing_activity_skus s ON s.id = b.activity_sku_id
    JOIN marketing_activities a ON a.id = s.activity_id
    JOIN marketing_participations p ON p.id = b.participation_id
    JOIN orders o ON o.id = p.order_id
    WHERE a.activity_code = q.activity_code AND b.user_id = q.user_id
      AND b.status = 'CLOSED' AND p.status = 'RELEASED' AND o.status = 'CANCELLED'
  )
  AND NOT EXISTS (
    SELECT 1 FROM marketing_bargains b
    JOIN marketing_activity_skus s ON s.id = b.activity_sku_id
    JOIN marketing_activities a ON a.id = s.activity_id
    WHERE a.activity_code = q.activity_code AND b.user_id = q.user_id
      AND b.status IN ('ACTIVE', 'QUALIFIED', 'ORDERED')
  );
