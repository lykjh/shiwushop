# 本地验证记录

2026-10-27 使用独立 MySQL 8、Redis 7 测试容器，从空 `shiwushop` 库启动：

- Flyway 成功执行 18 个迁移；数据库有 38 张表（含迁移历史表），商品表为空。
- `mvn verify` 运行 269 项测试：0 失败、0 错误、6 项按条件跳过，构建成功。
- `GET /actuator/health` 返回 HTTP 200、`UP`；`GET /products` 返回 HTTP 200 和空列表；`GET /users/csrf` 返回 HTTP 200。

此记录覆盖空库迁移、测试与本机 HTTP 冒烟。生产支付渠道、RabbitMQ、OpenSearch 和旧数据未参与该次验证。
