# 本地验证记录

2026-10-27 使用独立 MySQL 8、Redis 7 测试容器，从空 `shiwushop` 库启动：

- Flyway 成功执行 18 个迁移；数据库有 38 张表（含迁移历史表），商品表为空。
- `mvn verify` 运行 269 项测试：0 失败、0 错误、6 项按条件跳过，构建成功。
- `GET /actuator/health` 返回 HTTP 200、`UP`；`GET /products` 返回 HTTP 200 和空列表；`GET /users/csrf` 返回 HTTP 200。

此记录覆盖空库迁移、测试与本机 HTTP 冒烟。生产支付渠道、RabbitMQ、OpenSearch 和旧数据未参与该次验证。

## 2026-10-28：S02 搜索 PIT 释放

原实现打开 PIT 后只依赖两分钟到期；列表末页和搜索、数据库异常路径没有主动释放。参考 [OpenSearch 接口与相近项目实现](PIT_RESEARCH.md)，现由搜索网关按官方 `DELETE /_search/point_in_time` 请求释放最新 PIT ID；列表有下一页时保留 PIT，末页或异常时关闭。删除请求限时 1 秒，返回的逐项失败状态及请求异常只记日志，不改变原列表响应。

- 定向单元、HTTP 假服务与控制器切片测试：`mvn -B '-Dtest=DiscoveryServiceTests,SearchGatewayTests,DiscoveryControllerTests' test`，11 项通过；日志保存在本机 `logs/S02-targeted-tests.log`。
- 隔离 MySQL 8／Redis 7 的全套测试：`mvn -B '-Dspring.profiles.active=test' '-Dspring.config.import=optional:file:./target/no-local.yml' test`，272 项中 266 项通过、6 项按条件跳过，0 失败、0 错误；日志保存在本机 `logs/S02-full-tests.log`。测试连接参数按 README 指向 `127.0.0.1:13306` 和 `127.0.0.1:16379`。
- 测试覆盖末页释放、异常释放、下一页保留、响应更新 PIT ID、DELETE 请求体和删除失败不影响调用方。未连接真实 OpenSearch；真实集群的 PIT 活跃量与删除失败日志仍需在启用发现功能后核对。中途离开的浏览继续由两分钟 `keep_alive` 回收。

## M01 图片读取

2026-10-28 在独立分支 `codex/m01-media-streaming` 验证：

- 单元测试 `MediaReadUnitTests`：确认公开图片经过可见性检查后返回文件资源。
- Controller 切片测试 `MediaControllerSliceTests`：确认公开图片返回 `no-cache`、ETag，并在匹配时返回无正文的 304。
- 集成测试 `MediaSecurityTests`、`ReviewCreateTests`：使用隔离 MySQL、Redis 和真实图片，确认私有图片为 `no-store`，公开图片可重验证，商品下架、解除图片引用及评价隐藏后，旧 ETag 请求返回 404。
- 完整测试集运行 271 项，0 失败、0 错误、6 跳过；原始输出见 `logs/M01-full-tests.log`。新增撤销场景的定向测试输出见 `logs/M01-media-final-tests.log`。运行日志保存在本地，不提交到 Git。

## S02 与 M01 合并验证

2026-10-28 将两个独立分支合入集成分支，使用隔离 MySQL 8 和 Redis 7 运行完整测试集：274 项，0 失败、0 错误、6 跳过，构建成功。原始输出保存在本机 `logs/merge-S02-M01-full-tests.log`；该运行日志不提交到 Git。

## M02 图片上传小范围内存修复

2026-10-28 在 `codex/m02-upload-streaming` 分支验证，设计依据与剩余边界见 [M02_UPLOAD_STREAMING.md](M02_UPLOAD_STREAMING.md)。只替换上传服务的原图读取方式；接口、数据库和部署配置均未变化。

- 单元测试 `MediaUploadUnitTests`：2 项通过，覆盖流式读取、实际字节上限、哈希与临时文件清理；原始输出 `logs/M02-small-unit-final.log`。
- 切片测试 `MediaControllerSliceTests`：1 项通过；原始输出 `logs/M02-small-targeted.log`。
- 集成测试 `MediaSecurityTests`：隔离 MySQL 8 和 Redis 7 上 3 项通过；原始输出 `logs/M02-small-integration.log`。
- 完整 `mvn verify`：使用隔离库 `shiwushop_m02_small`，276 项中 270 项通过、6 项按条件跳过，0 失败、0 错误，JAR 打包成功；原始输出 `logs/M02-small-verify-final.log`。日志保存在本机，不提交到 Git。
