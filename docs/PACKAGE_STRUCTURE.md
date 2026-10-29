# 业务包结构

当前只按已实现的业务划分两个中心：

| 业务包 | 已归入的功能 |
| --- | --- |
| `com.shiwushop.catalog` | 商品、SKU、分类、品牌、店铺、评价、搜索及公开商品缓存 |
| `com.shiwushop.commerce` | 购物车、订单、支付、售后、物流及后台交易任务 |

各中心内沿用 `controller`、`dto`、`vo`、`entity`、`mapper`、`service` 分层。用户、地址、图片和通用鉴权等代码暂保留原位置；尚未实现的营销、会员等功能不建空包。

本次只迁移 Java 包和 MyBatis 类型引用，HTTP 路由、数据库表及业务规则未改。`ShiwushopApplication` 的 Mapper 扫描同时覆盖原有、商品中心和交易中心。

验证：隔离 MySQL（13306）和 Redis（16379）下运行 `mvn verify`，285 项测试中 279 项通过、6 项跳过、0 项失败；原始输出见 `logs/package-centers-verify.log`。工作区另有未提交的 `MediaServiceImpl` 包名变更，与现有测试引用不一致，因此验证在隔离源码副本中使用该文件的 Git 基线版本，未改动工作区文件。
