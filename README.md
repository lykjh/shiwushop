# shiwushop

基于 Java 21、Spring Boot 4、MyBatis-Plus 和 MySQL 8 的电商后端项目。提供账号与会话、商品与搜索、购物车和订单、支付宝支付与退款、物流、评价及商家管理接口。Redis 用于会话、风控和公开商品缓存；RocketMQ 用于物流消息；商品搜索使用 MySQL 全文索引。本仓库不包含前端。

## 项目结构

- `shiwushop-app`、`shiwushop-core`、`shiwushop-common` 和 `shiwushop-search`：控制器、业务服务、数据访问与基础组件。
- `shiwushop-core/src/main/resources/db/migration`：Flyway 建表和结构变更，当前为 V1—V45。
- `deploy/sql/create_database.sql`：MySQL 8 空库创建语句。
- `compose.test.yml`：隔离的 MySQL、Redis 测试环境。
- `deploy/app.yml`、`deploy/nginx/`：部署配置模板。

## 本地构建与测试

需要 JDK 21、Maven 和 Docker。以下 PowerShell 命令使用本机回环地址上的隔离测试容器；示例密码和加密密钥仅用于测试。

```powershell
docker compose -p shiwushop-trial -f compose.test.yml up -d --wait
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:13306/shiwushop?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:SPRING_DATASOURCE_USERNAME = 'shiwushop'
$env:SPRING_DATASOURCE_PASSWORD = 'test-shiwushop'
$env:SPRING_DATA_REDIS_HOST = '127.0.0.1'
$env:SPRING_DATA_REDIS_PORT = '16379'
$env:SHOP_PRIVACY_KEY_V1_BASE64 = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
mvn -B '-Dspring.profiles.active=test' '-Dspring.config.import=optional:file:./target/no-local.yml' verify
```

测试结束后可用 `docker compose -p shiwushop-trial -f compose.test.yml down -v` 清理该隔离环境及测试数据。若要从空库运行应用，重新启动测试容器，再设置 `$env:SPRING_PROFILES_ACTIVE='test'`、`$env:SERVER_ADDRESS='127.0.0.1'`、`$env:SERVER_PORT='18080'`，按[部署说明](docs/DEPLOYMENT.md#jwt-configuration)设置独立测试密钥。使用本地 HTTP 时，再设置 `SHOP_AUTH_JWT_COOKIE_PATH=/` 和 `SHOP_AUTH_JWT_COOKIE_SECURE=false`，然后执行 `java -jar shiwushop-app/target/shiwushop-app-1.0-SNAPSHOT.jar`。健康检查地址为 `http://127.0.0.1:18080/actuator/health`。

MySQL 容器自动创建测试库。手工安装数据库时先执行 [`create_database.sql`](deploy/sql/create_database.sql)；应用启动后 Flyway 执行 V1—V45，不导入业务数据。生产环境的依赖、凭据和启动步骤见[部署说明](docs/DEPLOYMENT.md)。

## 文档

- [API 路由概览](docs/API.md)
- [现有功能需求](docs/REQUIREMENTS.md)
- [部署说明](docs/DEPLOYMENT.md)
- [本地验证记录](docs/VERIFICATION.md)
- [贡献指南](CONTRIBUTING.md)与[安全报告方式](SECURITY.md)

最近一次隔离测试运行 269 项：0 失败、0 错误、6 项按条件跳过。真实支付渠道、生产消息队列与旧业务数据未参与该次验证。

## 许可证

本项目采用 [MIT 许可证](LICENSE)。

### 物流消息切换

使用 RocketMQ 前，核对 Broker 定时投递已开启、上限至少 24 小时，主题为 DELAY 且消费组存在。应用连接 NameServer，设置本次启动的 SHOP_SHIPMENT_ROCKET_PREFLIGHT_VERIFIED=true。应用部署模板直接要求核验值。

切换时停止旧 RabbitMQ 发布器及旧应用，保留已有 Outbox 和包裹。待发布事件由新版在截止前 23 小时内发送，旧版已发布且尚未确认的包裹继续由数据库到期扫描处理。上线后核对消费组连接、待发布事件和到期积压。

## 多包裹发货与支付配置

商家可用 `POST /merchant/shops/{shopId}/orders/{orderId}/shipments` 指定每个包裹的商品和件数。买家通过 `GET /orders/{orderId}/shipments` 查看包裹，通过 `POST /orders/{orderId}/shipments/{shipmentId}/confirm` 确认指定包裹。单包裹订单仍支持原来的 `/shipment` 接口。

数据库通过 Flyway 升级至 V42。V40 会回填旧包裹和售后件数，无法判定归属的旧记录标记为 `NEEDS_REVIEW`。全部有效件数收货后才记录整单收齐。

支付和退款默认沿用支付宝适配器。独立演示环境可以设置 `SHOP_PAYMENT_SIMULATION_ENABLED=true`，再通过 `POST /orders/{orderId}/payment/simulate` 显式确认模拟付款。模拟交易仅在数据库中记录，不会扣款。
