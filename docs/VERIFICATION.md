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

## M03 图片残留文件清理

2026-10-28 在 `codex/m03-media-orphan-cleanup` 分支验证，设计与边界见 [M03_MEDIA_CLEANUP.md](M03_MEDIA_CLEANUP.md)。

- 单元测试 `MediaOrphanCleanupTests`：2 项通过，覆盖过期无记录文件、临时文件、有效记录、新文件及数据库异常时的保留行为；原始输出 `logs/M03-unit.log`。
- 控制器切片 `MediaControllerSliceTests` 与图片集成 `MediaSecurityTests`：隔离 MySQL 8／Redis 7 上共 5 项通过，含清理器与真实数据库记录的核对；原始输出 `logs/M03-integration.log`。
- 完整 `mvn verify`：隔离库 `shiwushop_m02_small`，279 项中 273 项通过、6 项按条件跳过，0 失败、0 错误，JAR 打包成功；原始输出 `logs/M03-verify.log`。日志保存在本机，不提交到 Git。

## RabbitMQ 真实链路补测

2026-10-28 使用本机隔离 MySQL 8、Redis 7 和专用 RabbitMQ 容器及虚拟主机，启用 `shipment.rabbit.live`、`fault.live`、`manual-replay.live`、`backlog.live`、`dual.live`，实际执行此前按条件跳过的 6 项：**6 通过，0 失败，0 错误，0 跳过**。包含死信、人工重放、双消费者重复消息、两分钟积压和 broker 重启恢复。原始输出保存在本机 `logs/RABBIT-live-six-final.log`，不提交到 Git。

首次补测发现双消费者用例在数据库时间尚未到期时发送消息；MySQL 将发货时间舍入到下一秒，消费记录已写入而运单仍待确认。测试现等待数据库读回的到期时间再投递。积压用例同时等待消费者完全停止及消息全部入队，避免异步时序造成误报。生产代码未修改。

常规 `mvn verify` 再次运行 279 项，0 失败、0 错误、6 项按 live 开关跳过，JAR 打包成功；原始输出 `logs/RABBIT-default-verify.log`。上述 live 补测已单独执行这 6 项。

## 并发复现修复

2026-10-28，在 `codex/fix-race-findings` 分支处理分类互相移动成环、转让后旧店主更新 SKU、购物车写入影响 0 行仍返回成功三项。参考 [mall 的购物车接口](https://github.com/macrozheng/mall/blob/master/mall-portal/src/main/java/com/macro/mall/portal/controller/OmsCartItemController.java) 对写入结果的处理方式，最终按本项目的异常契约返回 404；分类与店铺使用现有事务和数据库行锁，不复制外部实现。

- 单元测试：`mvn -B '-Dtest=CartWriteRaceUnitTests' test`，2 项通过；原始输出保存在本机 `logs/race-cart-unit.log`。
- 集成测试：本机独立 MySQL 8.0.29 与 Redis 绑定 `127.0.0.1:13306/16379`，`CategoryMoveRaceTests` 和 `ShopOwnerSkuRaceTests` 共 2 项通过；原始输出 `logs/race-integration.log`。
- 完整 `mvn verify`：283 项中 276 项通过、1 项失败、6 项按条件跳过。唯一失败是本轮开始前已有未提交改动的 `MerchantSkuUpdateTests` 新增的 `MERCHANT_ADJUST` 库存流水断言；SKU 更新实现尚未写该流水。首次运行另有本地 MySQL 连接数耗尽，调高这个隔离实例的连接上限后重跑，剩余失败仅此一项。原始输出 `logs/race-full-verify.log` 与 `logs/race-full-verify-retry.log`。
- 本次只修改 SKU 更新路径的店主转让竞态；其他商家写入路径仍需逐一检查归属校验与事务写入间的时序。

## 店主转让与其他商家写入的并发复现

2026-10-28 在 `codex/shop-owner-race-tests` 分支用隔离的本机 MySQL 8.0.29 与 Redis，先复跑 `ShopOwnerSkuRaceTests`：1 项通过，旧店主在转让提交后更新 SKU 被拒绝；原始输出 `logs/owner-race-sku.log`。

显式运行 `mvn -B '-Dtest=ShopOwnerOtherWriteRaceRepro' '-Dspring.profiles.active=test' '-Dspring.config.import=optional:file:./target/no-local.yml' test`。用例在三项真实服务调用完成店铺归属校验后暂停，提交店主转让，再放行写入。结果：旧店主新增商品成功（数据库新增 1 行）、发货成功（新增 1 行）、售后审核成功（状态 `REQUESTED` 变为 `REFUNDING`）。用例要求三项都拒绝，因此报告 1 项失败、内部 6 个断言失败；原始输出 `logs/owner-race-other-write.log`。测试在 `finally` 清理创建的数据，类名使用 `Repro`，不纳入普通 `mvn verify` 扫描。其余同样使用普通归属读取的商家写入方法尚未逐项执行并发交错测试。

## 店主转让与商家写入修复

2026-10-28 在 `codex/fix-owner-write-races` 分支将商品创建、修改、规格替换、SKU 创建和上下架、商家发货、售后审核、退货签收、发起退款及退款同步共 10 条写入路径改为在事务中先锁定店铺归属，再锁商品或订单。店主转让也先锁店铺。退款渠道调用继续在数据库事务外执行；事务内已受理的退款尝试继续由原有幂等状态结算。店铺资料修改仍由含 `owner_user_id` 条件的更新 SQL 保护。

- 并发测试 `ShopOwnerOtherWriteRaceTests`：10 条路径均在转让提交后返回 404，原有商品 1 行、发货 0 行、售后状态仍为 `REQUESTED`。`ShopOwnerSkuRaceTests` 验证已获授权的 SKU 更新先完成、转让随后完成。两项均通过，原始输出 `logs/owner-fix-race-tests.log`。
- 商品、SKU、物流、售后、店铺相关 40 项测试全部通过，原始输出 `logs/owner-fix-module-tests.log`。
- 完整 `mvn verify`：284 项中 277 项通过、1 项失败、6 项按条件跳过。唯一失败是本轮开始前已有未提交改动的 `MerchantSkuUpdateTests` 所要求的 `MERCHANT_ADJUST` 库存流水；现有 SKU 更新路径尚未写入该流水。原始输出 `logs/owner-fix-full-verify.log`。

## 商家写入竞态与库存流水最终验证

2026-10-29 在 `codex/sku-stock-movement` 分支完成前述商家写入修复的回归，并补齐 SKU 库存调整流水。商家修改库存时，在同一数据库事务内写入 `MERCHANT_ADJUST` 流水；只修改价格时不新增库存流水。此前 `MerchantSkuUpdateTests` 的断言现已通过。未修改用户已有的 `RateLimiter.java` 工作区改动。

- 定向集成测试：`MerchantSkuUpdateTests`、`ShopOwnerOtherWriteRaceTests`、`ShopOwnerSkuRaceTests` 共 5 项通过，0 失败、0 错误。并发用例逐项覆盖商品创建、修改、规格替换、SKU 创建、状态更新、发货、售后审核、退货签收、退款发起和退款同步；转让先提交时，旧店主的 10 条请求均返回 404。原始输出保存在本机 `logs/sku-stock-movement-targeted.log`。
- 完整 `mvn verify`：285 项，0 失败、0 错误、6 项按条件跳过，JAR 构建成功；原始输出保存在本机 `logs/sku-stock-movement-full-verify-retry.log`。首次全套运行时隔离 MySQL 的连接上限为 151，后段 3 个测试上下文因 `Too many connections` 无法启动；将该测试实例的上限设为 500 后重跑通过。首次运行记录为 `logs/sku-stock-movement-full-verify.log`。这些日志保存在本机，不提交到 Git。
