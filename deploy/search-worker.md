# Java 搜索事件消费者

`shiwushop` 的搜索事件消费者运行在 Java 应用中，启用前应完成索引准备与增量追平。

## 启用顺序

1. 初始全量索引期间保持 `SHOP_SEARCH_WORKER_ENABLED=false`、`SHOP_SEARCH_DISCOVERY_ENABLED=false`。
2. 核对商品、店铺、品牌索引数量和样本，确认 OpenSearch 的 `shiwushop-products-v2`、`shiwushop-shops-v1`、`shiwushop-brands-v1` 就绪。为 Java 应用配置可从容器内访问的 `SHOP_SEARCH_URL`。
3. 只将 `SHOP_SEARCH_WORKER_ENABLED` 改成 `true` 并重启应用。Java 调度线程每轮最多处理一个事件批次，每批最多 200 个关联商品；数据库租约防止多个实例同时推进同一事件。搜索发现接口仍关闭。
4. 查看应用日志的 `搜索事件积压 pending=... dead=... oldestPendingSeconds=...`。待 `pending=0`、`dead=0`，再次核对数据库与索引数量及样本，再切换读别名，最后启用 `SHOP_SEARCH_DISCOVERY_ENABLED=true`。仅积压为零不足以证明全量索引正确。

## 监控和清理

Java 应用每分钟输出积压量、死信数和最老待办秒数。发现接口启用后，`dead>0` 立即调查；最老待办超过 120 秒且连续两次出现时，检查数据库、OpenSearch 和应用日志。索引不可用或积压持续增长时关闭发现接口，恢复后重新核对。

发现功能关闭期间，`PENDING`、`DEAD` 事件保留至重放或人工处置完成，不能按时间删除；每日检查积压及数据库磁盘余量。`PUBLISHED` 搜索事件保留 30 天，完成数据库备份后按批清理，每批至多 1000 条：

```sql
DELETE FROM outbox_events
WHERE event_type IN ('SEARCH_PRODUCT_REFRESH', 'SEARCH_SHOP_REFRESH',
                     'SEARCH_BRAND_REFRESH', 'SEARCH_CATEGORY_REFRESH')
  AND status = 'PUBLISHED'
  AND published_at < NOW() - INTERVAL 30 DAY
LIMIT 1000;
```

回滚时关闭发现接口和消费者，保留待处理事件供后续重放。消费者根据当前数据库状态重建索引文档；bulk 写入由固定文档 ID 保证重复投递可覆盖，只有成功批次才推进事件游标或标记发布。

## 方法选择

参考 [Debezium outbox 设计](https://github.com/debezium/debezium/blob/main/documentation/modules/ROOT/pages/transformations/outbox-event-router.adoc) 保留事务内事件写入和异步投递边界。项目已经有 outbox、MyBatis、`SearchGateway` 与 Spring 定时任务，因此用现有 Java 技术栈实现，未引入消息平台或另一运行时。与同步索引相比，业务事务不受搜索故障回滚影响；代价是短时索引延迟，必须观察积压并设置启用门槛。搜索任务使用单独的 Spring 调度线程，避免索引等待占用支付和物流的默认调度线程。
