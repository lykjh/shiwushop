# 部署说明

## 构建与数据库

1. 使用 JDK 21 和 Maven 执行 `mvn -B verify`，确认测试通过并生成 `target/shiwushop-1.0-SNAPSHOT.jar`。
2. 使用 MySQL 8，执行 [`deploy/sql/create_database.sql`](../deploy/sql/create_database.sql) 创建空 `shiwushop` 库，并为应用账号授予该库需要的迁移和读写权限。
3. 配置 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、Redis 连接参数和 `SHOP_PRIVACY_KEY_V1_BASE64`。凭据通过受保护的环境变量或本地配置注入，不提交到仓库。应用首次启动时 Flyway 执行 [Flyway 迁移](../shiwushop-core/src/main/resources/db/migration)。
4. 配置持久化媒体目录 `SHOP_MEDIA_ROOT`，启动 JAR，检查 `/actuator/health` 返回 `UP`，再验证注册、登录、商品和订单主路径。

[`deploy/app.yml`](../deploy/app.yml) 是应用容器配置模板；镜像需由部署环境构建并提供对应 MySQL、Redis 服务。启用物流时提供 RabbitMQ 并设置 `SHOP_SHIPMENT_RABBIT_ENABLED=true`。支付宝渠道配置须分别使用对应环境的应用 ID、商户 ID、密钥及回调地址。

## 商品搜索

Flyway V45 为商品、店铺和品牌名称建立 MySQL 全文索引。使用默认的 `ngram_token_size=2`。已有数据的环境应先在同规模副本验证建索引时间和锁影响。多实例部署时配置相同的 `SHOP_SEARCH_CURSOR_SECRET`，至少 32 字节。

## 运行约束

- 生产配置不得使用 `compose.test.yml` 中的测试账号或测试加密密钥。
- 对外代理只转发业务接口；健康与指标端点限制在可信网络。反向代理配置见 [`deploy/nginx/shiwushop.conf`](../deploy/nginx/shiwushop.conf)。
- 发布前备份数据库并验证恢复流程。Flyway 迁移失败时保留现场，不手工改写 `flyway_schema_history`；回退应用前核对数据库结构兼容性。
- 本仓库不包含旧业务数据；空库启动仅建立结构。

## JWT configuration

Set `SHOP_AUTH_JWT_SECRET` in the deployment environment before starting the application. Use the same Base64-encoded key on every instance, generated from at least 32 random bytes. Keep it outside Git. Rotating the key invalidates existing login tokens and browser flow credentials. Existing Spring Session users need to log in again after the upgrade.

The Compose template defaults the auth cookie path to `/` for root-level routes. For a storefront served only under `/api`, use `SHOP_AUTH_JWT_COOKIE_PATH=/api`. Serve the login and CSRF cookies over HTTPS.
