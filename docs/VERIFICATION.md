# 本地验证记录

2026-10-27 使用独立 MySQL 8、Redis 7 测试容器，从空 `shiwushop` 库启动：

- Flyway 成功执行 18 个迁移；数据库有 38 张表（含迁移历史表），商品表为空。
- `mvn verify` 运行 269 项测试：0 失败、0 错误、6 项按条件跳过，构建成功。
- `GET /actuator/health` 返回 HTTP 200、`UP`；`GET /products` 返回 HTTP 200 和空列表；`GET /users/csrf` 返回 HTTP 200。

此记录覆盖空库迁移、测试与本机 HTTP 冒烟。生产支付渠道、RabbitMQ、OpenSearch 和旧数据未参与该次验证。

## M01 图片读取

2026-10-28 在独立分支 `codex/m01-media-streaming` 验证：

- 单元测试 `MediaReadUnitTests`：确认公开图片经过可见性检查后返回文件资源。
- Controller 切片测试 `MediaControllerSliceTests`：确认公开图片返回 `no-cache`、ETag，并在匹配时返回无正文的 304。
- 集成测试 `MediaSecurityTests`、`ReviewCreateTests`：使用隔离 MySQL、Redis 和真实图片，确认私有图片为 `no-store`，公开图片可重验证，商品下架、解除图片引用及评价隐藏后，旧 ETag 请求返回 404。
- 完整测试集运行 271 项，0 失败、0 错误、6 跳过；原始输出见 `logs/M01-full-tests.log`。新增撤销场景的定向测试输出见 `logs/M01-media-final-tests.log`。运行日志保存在本地，不提交到 Git。
