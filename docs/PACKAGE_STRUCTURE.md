# 业务包结构

顶层按已实现的业务和共享能力划分，新增业务落地时再增加模块：

| 业务包 | 已归入的功能 |
| --- | --- |
| `com.shiwushop.catalog` | 商品、SKU、分类、品牌、店铺、评价、搜索及公开商品缓存 |
| `com.shiwushop.commerce` | 购物车、订单、支付、售后、物流及后台交易任务 |
| `com.shiwushop.account` | 用户、注册登录、账号风控、收货地址及地址隐私 |
| `com.shiwushop.common` | 统一响应、异常、会话安全、滑块、图片文件处理、日志、分页及消息发件箱 |

业务模块内沿用 `controller`、`dto`、`vo`、`entity`、`mapper`、`service` 分层。业务状态枚举归各自模块。图片文件由 `common.media` 保存和读取；现有商品、店铺、品牌与评价图片的公开引用判断由 `catalog.mapper.media` 执行，后续头像、证件等由其所属业务模块管理。尚未实现的功能不建空包。

包整理只迁移 Java 类型和 MyBatis 类型引用，HTTP 路由、数据库表及业务规则未改。`ShiwushopApplication` 的 Mapper 扫描覆盖三个现有业务模块及共享图片、发件箱 Mapper。

验证：隔离 MySQL（13306）和 Redis（16379）下运行 `mvn verify`，285 项中 279 项通过、6 项跳过、0 项失败；原始输出见 `logs/package-shared-verify.log`。图片读取与评价场景的定向测试输出见 `logs/package-shared-media.log`。
