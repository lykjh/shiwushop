# shiwushop 后端需求文档（现有实现基线）

更新日期：2026-10-28。本文从当前 `src/main` 的接口、服务和配置整理已实现的业务需求，供后续产品设计与验收使用。接口字段、错误码和状态流转以代码为准；路由索引见 [API 路由概览](API.md)。本仓库只包含后端，不包含前端页面。

## 1. 产品范围与角色

系统提供商品浏览、账号、购物车、下单、支付、发货、售后和评价能力。

| 角色 | 已实现的主要操作 |
| --- | --- |
| 游客 | 浏览可见分类、品牌、店铺、商品、公开评价；获取 CSRF 令牌和滑块；注册、登录 |
| 买家 | 管理自己的地址和购物车；结算、查看自己的订单、支付、确认收货、申请售后、评价及举报评价 |
| 商家 | 管理自己店铺的资料、商品和 SKU；查看店铺订单、发货、处理售后 |
| 管理员 | 管理分类、品牌、店铺及商品状态；处理评价举报 |

普通注册账号的角色为 `USER`。当前代码没有公开的管理员创建接口，也没有预设管理员账号。

## 2. 功能需求

### F01 账号、会话与安全

- 用户可注册、登录、退出、查看本人信息和修改密码。注册始终需要当前会话的一次性滑块凭证；登录在风险规则要求时需要滑块。滑块生成与校验分别通过 `/users/generate`、`/users/valid` 完成。
- 登录成功后更换 Session ID；会话 Cookie 名为 `MALLSESSION`。修改密码使旧鉴权版本失效。密码以 BCrypt 散列保存，不保存明文。
- 修改类请求使用 CSRF 保护；客户端可先调用 `GET /users/csrf` 获取令牌。支付宝通知回调使用独立验签流程。
- 注册、登录和滑块入口实施流量与风险控制；触发挑战时返回 `403` 和 `data.state=CAPTCHA_REQUIRED`，入口额度耗尽时返回 `429`。Redis 用于会话及风控状态。

实现依据：[注册入口](../src/main/java/com/shiwushop/account/controller/user/RegisterController.java)、[登录与会话入口](../src/main/java/com/shiwushop/account/controller/user/LoginController.java)、[滑块入口](../src/main/java/com/shiwushop/account/controller/user/CaptchaController.java)；各自调用对应服务，注册与登录共用鉴权风险组件。

结构拆分验证：账号相关定向测试 28 项通过；完整回归使用 `mvn -q '-Dspring.datasource.hikari.maximum-pool-size=2' '-Dspring.test.context.cache.maxSize=4' verify`，300 项运行、0 失败、0 错误、6 项按现有配置跳过。输出保存在 `logs/split-user-auth-targeted.log` 与 `logs/split-user-auth-verify.log`。

### F02 公开目录与商品

- 游客可查看可见分类、品牌、启用店铺及可售商品；不可向公众暴露已下架商品或停用店铺的商品详情。
- 商品列表支持分类、品牌、店铺、价格区间和 `afterId` 游标；默认每批 20 条，不接受 `page` 或 `includeTotal`。商品详情展示与当前商品对应的信息。
- 商家可在自己店铺内创建、修改商品及 SKU、维护规格与状态，并查询自己的商品。管理员可调整商品公开状态。
- 公开商品列表使用 Redis 缓存；失效后的回源仍以数据库可见性和价格数据为准。

实现依据：[商品控制器](../src/main/java/com/shiwushop/controller/product)、[ProductServiceImpl](../src/main/java/com/shiwushop/service/Impl/ProductServiceImpl.java)、[PublicProductCache](../src/main/java/com/shiwushop/catalog/cache/PublicProductCache.java)。

### F03 地址、购物车与结算

- 买家可新增、查询、修改和删除自己的收货地址。收货人、手机号及详细地址在落库前加密；地址读写依赖稳定的 32 字节加密密钥。
- 买家可添加、查看、修改和移除购物车项；结算时重新核对商品状态、价格、库存和地址，价格变化或库存不足应明确返回冲突结果。
- 支持购物车结算和直接购买。一次购物车结算最多 100 项；结算请求使用幂等键，重复请求不得生成重复订单。新订单支付有效期为 15 分钟。

实现依据：[AddressServiceImpl](../src/main/java/com/shiwushop/service/Impl/AddressServiceImpl.java)、[CartServiceImpl](../src/main/java/com/shiwushop/service/Impl/CartServiceImpl.java)、[PrivacyCipher](../src/main/java/com/shiwushop/common/privacy/PrivacyCipher.java)。

### F04 订单、支付与退款

- 买家可按本人身份查看订单列表和详情，并发起订单支付、查看支付状态及主动同步支付结果。
- 支付渠道为支付宝；二维码生成、通知验签和主动同步均需与订单和支付尝试状态一致。支付过期任务处理超时订单及库存归还。
- 售后退款由商家在已建立的售后流程中发起；退款同步与后台对账处理待确认结果。支付与退款的真实渠道调用需要对应环境的支付宝配置。

实现依据：[订单与支付控制器](../src/main/java/com/shiwushop/controller)、[PaymentServiceImpl](../src/main/java/com/shiwushop/service/Impl/PaymentServiceImpl.java)、[支付后台任务](../src/main/java/com/shiwushop/commerce/payment)。

### F05 物流、售后与评价

- 商家可为已支付且符合发货条件的订单录入运单；买家与所属商家可查看运单。买家可确认收货；发货 10 天后可自动确认，退款条件仍需核对。
- 买家可对自己的订单申请售后、提交退货运单并查看进度；商家可审核、确认收货、发起退款或同步退款状态。
- 已确认收货且未全额退款的订单明细可评价一次。评价支持一次追评、本人删除和其他登录用户举报；管理员可处理举报。公开评价列表与统计只展示符合可见性规则的内容。
- 启用 RabbitMQ 时，物流到期确认通过事务 outbox 投递与消费去重处理；未启用时仍有到期扫描任务。

实现依据：[ShipmentServiceImpl](../src/main/java/com/shiwushop/service/Impl/ShipmentServiceImpl.java)、[AfterSaleServiceImpl](../src/main/java/com/shiwushop/service/Impl/AfterSaleServiceImpl.java)、[ReviewServiceImpl](../src/main/java/com/shiwushop/service/Impl/ReviewServiceImpl.java)、[物流消息组件](../src/main/java/com/shiwushop/commerce/shipment)。

### F06 图片与搜索

- 登录用户可上传图片；服务端限制原图 10 MiB、像素总数 2000 万，并对格式与归属进行校验。图片读取根据媒体状态及业务对象可见性决定访问权限；失去引用的文件由后台清理。
- 默认商品浏览走数据库公开列表。可选的 OpenSearch 发现能力提供关键词、筛选、不透明游标和已加载卡片刷新；索引未就绪时返回 `503`。搜索建议提供商品、店铺和品牌入口。

实现依据：[MediaServiceImpl](../src/main/java/com/shiwushop/service/Impl/MediaServiceImpl.java)、[MediaOrphanCleanup](../src/main/java/com/shiwushop/service/Impl/MediaOrphanCleanup.java)、[搜索组件](../src/main/java/com/shiwushop/catalog/search)。

## 3. 接口与运行要求

- 业务 JSON 响应包含 `code`、`data`、`message`；各接口应使用相应 HTTP 状态表达参数错误、未授权、资源不存在、状态冲突、限流和依赖不可用。
- 买家资源按当前会话的用户 ID 隔离；商家操作校验店铺归属；管理员接口校验 `ADMIN` 角色。
- MySQL 8 保存业务数据，由 Flyway 管理结构；Redis 保存会话、风控及部分公开缓存。图片存储目录需持久化。
- 生产部署需要数据库、Redis 和地址加密密钥。RabbitMQ、OpenSearch 发现和支付宝真实交易分别依赖对应服务与凭据；功能开关及启动条件见[部署说明](DEPLOYMENT.md)。
- 应提供健康检查、请求日志、可按需启用的方法追踪以及测试记录；健康与指标端点只向可信网络开放。

## 4. 当前边界与验收

- 前端、管理员账号初始化流程及真实支付渠道凭据不在当前仓库中；本文不把它们写成已交付功能。
- 常规验收覆盖注册与登录、权限和 CSRF、商品可见性、结算幂等、库存与价格冲突、支付和退款状态、物流去重、图片访问及评价权限。运行命令和已有结果见[本地验证记录](VERIFICATION.md)。
- RabbitMQ 真实链路测试需显式启用 live 开关；默认测试集会跳过这 6 项。OpenSearch 与支付宝真实渠道需在具备独立环境后验证。
