# RabbitMQ 4.x 与物流仲裁队列

`deploy/rabbitmq.yml` 为新环境提供 RabbitMQ 4.4.0，使用持久化数据卷，连接参数与 `deploy/app.yml` 对应。部署已有 RabbitMQ 的环境先核对服务端版本及数据卷，再决定升级方式；不要把新模板直接覆盖到现有 broker 的数据卷上。

物流延迟、消费、死信三个队列均声明为 `quorum`。延迟消息仍使用每条消息的 TTL，过期后经死信交换机进入消费队列；前两个队列使用 `at-least-once` 死信转发和 `reject-publish`。消息发布为持久化并等待 broker 确认，消费者以数据库 `consumed_events` 去重。

## 已有经典队列的切换

RabbitMQ 的队列类型在创建时确定，**已有同名经典队列不能直接变成仲裁队列**。上线本次应用前，在 broker 管理界面核对以下三个队列的 `type`、消息数和消费者数：`shop.shipment.confirm.delay`、`shop.shipment.confirm`、`shop.shipment.confirm.dead`。已有经典队列时，先保留旧应用和队列，制定消息迁移及死信处置方案；不能通过删除有积压的队列来完成升级。队列类型、路由及积压处理完成后再启用本次应用的 RabbitMQ 物流开关。切换期间可关闭该开关，数据库到期扫描仍会确认到期运单。

仲裁队列中过期消息到达**队首**才转发。不同运单的 TTL 不同时，较短延迟的消息可能被前面较长延迟的消息挡住；数据库到期扫描是业务时效的兜底，需监控待确认运单积压和 `oldestPendingSeconds`。单节点 RabbitMQ 4.x 可运行仲裁队列，但不提供多节点故障容错。

依据：[RabbitMQ 仲裁队列文档](https://www.rabbitmq.com/docs/4.1/quorum-queues)、[RabbitMQ TTL 文档](https://www.rabbitmq.com/docs/ttl)。

## 本地验证（2026-11-01）

- `docker compose -f deploy/app.yml -f deploy/rabbitmq.yml config --quiet` 通过。
- 隔离 RabbitMQ 4.4.0 的 `rabbitmqctl list_queues` 确认三个队列实际类型均为 `quorum`。
- `ShipmentRabbitLiveTests` 的到期转发、非法消息死信及 broker 重启恢复 3 项通过；人工重放、双消费者和两分钟积压 3 项未启用。日志：`logs/rabbitmq-quorum-live-final.log`。
- `mvn verify`：302 项、0 失败、0 错误、6 项按开关跳过。日志：`logs/rabbitmq-quorum-verify.log`。日志仅保存在本地，不提交到 Git。
