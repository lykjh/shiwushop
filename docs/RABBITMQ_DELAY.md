# RabbitMQ 4.x 物流延迟消息

`deploy/rabbitmq.yml` 为新环境提供 RabbitMQ 4.4.0。物流继续使用经典持久化队列：发布到 `shop.shipment.confirm.delay` 时为每条消息设置 TTL；到期后通过死信交换机转发至 `shop.shipment.confirm`。消费失败的消息进入 `shop.shipment.confirm.dead`。发布者等待 broker 确认，消费者用数据库记录去重。

不同消息的 TTL 不同时，已到期消息可能被队首未到期消息挡住；数据库到期扫描仍负责兜底确认运单。上线前核对现有 broker 版本、数据卷及三个队列的积压，不要用新空数据卷替换正在使用的 broker。

本地测试：隔离 RabbitMQ 4.4.0 中三个队列均确认为 `classic`；定向单元、配置和真实 MQ 链路共 12 项、0 失败、0 错误、3 项按开关跳过，涵盖到期转发、死信和 broker 重启恢复。日志位于本地 `logs/rabbitmq-classic-ttl-live.log`。先前仲裁队列实验已撤回。

最终 `mvn verify`：302 项、0 失败、0 错误、6 项按开关跳过；日志位于本地 `logs/rabbitmq-classic-ttl-verify.log`。

依据：[RabbitMQ TTL 文档](https://www.rabbitmq.com/docs/ttl)。
