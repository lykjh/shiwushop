-- 仅在独立 shiwushop_rocket 测试库执行；ROLLBACK 清理本事务 100100 条样本。
START TRANSACTION;
INSERT INTO outbox_events(event_type,aggregate_type,aggregate_id,payload,status,attempts,next_attempt_at)
WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<1000)
SELECT 'SHIPMENT_AUTO_CONFIRM','SHIPMENT',100000000+a.n*100+b.n,
       JSON_OBJECT('shipmentId',100000000+a.n*100+b.n),'PENDING',0,DATE_ADD(NOW(),INTERVAL 9 DAY)
FROM seq a JOIN seq b ON b.n<=100;
INSERT INTO outbox_events(event_type,aggregate_type,aggregate_id,payload,status,attempts,next_attempt_at)
WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<100)
SELECT 'SHIPMENT_AUTO_CONFIRM','SHIPMENT',200000000+n,JSON_OBJECT('shipmentId',200000000+n),'PENDING',0,NOW() FROM seq;
EXPLAIN ANALYZE SELECT id,aggregate_id,attempts FROM outbox_events
WHERE event_type='SHIPMENT_AUTO_CONFIRM' AND status='PENDING'
AND next_attempt_at<=NOW() AND (claim_until IS NULL OR claim_until<=NOW()) ORDER BY id LIMIT 50;
ROLLBACK;
