# API 路由概览

接口实现以当前 Controller、鉴权配置和请求 DTO 为准。本文提供路由与访问范围导航；写请求使用 `MALLSESSION` 会话及 `X-CSRF-TOKEN`，先调用 `GET /users/csrf` 获取令牌。业务 JSON 响应包含 `code`、`data`、`message`。商品公开列表支持 `size` 与 `afterId` 游标。

## 运行路由

### 账号与会话

| 方法 | 路径 | 访问范围 |
| --- | --- | --- |
| `GET` | `/users/csrf` | 公开 |
| `POST` | `/users/register` | 公开 |
| `POST` | `/users/login` | 公开 |
| `POST` | `/users/sms/send` | 公开；手机号发送登录码，未绑定号码也返回相同成功结构 |
| `POST` | `/users/sms/login` | 公开；手机号和 6 位验证码，成功建立 `MALLSESSION` |
| `POST` | `/users/phone/bind/send` | 已登录；向新手机号发送绑定码 |
| `POST` | `/users/phone/bind/confirm` | 已登录；当前密码、手机号和绑定码，成功后旧会话失效 |
| `POST` | `/users/logout` | 登录及资源归属 |
| `POST` | `/users/password` | 登录及资源归属 |
| `GET` | `/users/me` | 登录及资源归属 |

### 公开目录

| 方法       | 路径                                     | 访问范围                                                      |
| -------- | -------------------------------------- | --------------------------------------------------------- |
| `GET`    | `/categories/{id}`                     | 公开                                                        |
| `GET`    | `/categories`                          | 公开                                                        |
| `GET`    | `/brands`                              | 公开                                                        |
| `GET`    | `/shops`                               | 公开                                                        |
| `GET`    | `/shops/{id}`                          | 公开                                                        |
| `GET`    | `/products`                            | 公开                                                        |
| `GET`    | `/products/{id}`                       | 公开                                                        |
| `GET`    | `/products/{productId}/reviews`        | 公开；按评价 ID 倒序，支持 `size` 与 `afterId`                        |
| `GET`    | `/products/{productId}/review-summary` | 公开；仅计算可见且未全额退款的评价，返回 `reviewCount` 和两位小数的 `averageRating` |
| `POST`   | `/reviews/{reviewId}/follow-up`        | 原评价买家；仅可追评一次，正文 1–2000 字，图片最多 9 张                         |
| `DELETE` | `/reviews/{reviewId}`                  | 原评价买家；软删除，重复删除返回成功且不可重新评价                                 |
| `POST`   | `/reviews/{reviewId}/reports`          | 登录用户；公开评价可举报，每人每条一次，重复提交返回成功且不覆盖原因                        |
| `GET`    | `/discovery/products`                  | 公开；支持搜索、筛选与不透明游标，索引未就绪时 503                               |
| `GET`    | `/discovery/products/refresh`          | 公开；按最多 60 个 ID 刷新已加载卡片                                    |
| `GET`    | `/search/suggestions`                  | 公开；分组返回商品、店铺、品牌入口                                         |

### 地址、购物车和结算

| 方法 | 路径 | 访问范围 |
| --- | --- | --- |
| `POST` | `/addresses` | 登录及资源归属 |
| `GET` | `/addresses` | 登录及资源归属 |
| `PUT` | `/addresses/{id}` | 登录及资源归属 |
| `DELETE` | `/addresses/{id}` | 登录及资源归属 |
| `POST` | `/cart/items` | 登录及资源归属 |
| `GET` | `/cart/items` | 登录及资源归属 |
| `PUT` | `/cart/items/{id}` | 登录及资源归属 |
| `DELETE` | `/cart/items/{id}` | 登录及资源归属 |
| `POST` | `/checkouts` | 登录及资源归属 |
| `POST` | `/checkouts/direct` | 登录及资源归属 |

### 订单与支付

| 方法 | 路径 | 访问范围 |
| --- | --- | --- |
| `GET` | `/orders/{id}` | 登录及资源归属 |
| `GET` | `/orders` | 登录及资源归属 |
| `POST` | `/orders/{orderId}/items/{orderItemId}/review` | 订单买家且已确认收货；创建一次评价 |
| `POST` | `/orders/{orderId}/payment` | 登录及资源归属 |
| `GET` | `/payments/checkout/{outTradeNo}` | 公开 |
| `GET` | `/orders/{orderId}/payment` | 登录及资源归属 |
| `POST` | `/orders/{orderId}/payment/sync` | 登录及资源归属 |
| `POST` | `/payments/alipay/notify` | 支付宝回调验签 |

### 物流与售后

| 方法 | 路径 | 访问范围 |
| --- | --- | --- |
| `POST` | `/merchant/shops/{shopId}/orders/{orderId}/shipment` | 商家及店铺归属 |
| `GET` | `/merchant/shops/{shopId}/orders/{orderId}/shipment` | 商家及店铺归属 |
| `GET` | `/orders/{orderId}/shipment` | 登录及资源归属 |
| `POST` | `/orders/{orderId}/shipment/confirm` | 登录及资源归属 |
| `POST` | `/orders/{orderId}/after-sales` | 登录及资源归属 |
| `GET` | `/orders/{orderId}/after-sales` | 登录及资源归属 |
| `GET` | `/after-sales/{caseId}` | 登录及资源归属 |
| `POST` | `/after-sales/{caseId}/return-shipment` | 登录及资源归属 |
| `GET` | `/merchant/shops/{shopId}/after-sales/{caseId}` | 商家及店铺归属 |
| `GET` | `/merchant/shops/{shopId}/after-sales` | 商家及店铺归属 |
| `POST` | `/merchant/shops/{shopId}/after-sales/{caseId}/review` | 商家及店铺归属 |
| `POST` | `/merchant/shops/{shopId}/after-sales/{caseId}/receive` | 商家及店铺归属 |
| `POST` | `/merchant/shops/{shopId}/after-sales/{caseId}/refund` | 商家及店铺归属 |
| `POST` | `/merchant/shops/{shopId}/after-sales/{caseId}/refund/sync` | 商家及店铺归属 |

### 商家

| 方法 | 路径 | 访问范围 |
| --- | --- | --- |
| `PUT` | `/merchant/shops/{id}` | 商家及店铺归属 |
| `GET` | `/merchant/shops` | 商家及店铺归属 |
| `GET` | `/merchant/shops/{id}/orders` | 商家及店铺归属 |
| `GET` | `/merchant/shops/{shopId}/orders/{orderId}` | 商家及店铺归属 |
| `POST` | `/merchant/products` | 商家及店铺归属 |
| `PUT` | `/merchant/products/{id}` | 商家及店铺归属 |
| `PUT` | `/merchant/products/{id}/options` | 商家及店铺归属 |
| `POST` | `/merchant/products/{id}/skus` | 商家及店铺归属 |
| `PUT` | `/merchant/products/{productId}/skus/{skuId}` | 商家及店铺归属 |
| `PUT` | `/merchant/products/{id}/status` | 商家及店铺归属 |
| `GET` | `/merchant/products` | 商家及店铺归属 |
| `GET` | `/merchant/products/{id}` | 商家及店铺归属 |

### 管理员

| 方法 | 路径 | 访问范围 |
| --- | --- | --- |
| `POST` | `/admin/brands` | 管理员 |
| `GET` | `/admin/brands` | 管理员 |
| `PUT` | `/admin/brands/{id}` | 管理员 |
| `POST` | `/admin/categories` | 管理员 |
| `GET` | `/admin/categories` | 管理员 |
| `PUT` | `/admin/categories/{id}` | 管理员 |
| `DELETE` | `/admin/categories/{id}` | 管理员 |
| `POST` | `/admin/shops` | 管理员 |
| `PUT` | `/admin/shops/{id}/owner` | 管理员 |
| `PUT` | `/admin/shops/{id}/status` | 管理员 |
| `GET` | `/admin/shops` | 管理员 |
| `PUT` | `/admin/products/{id}/status` | 管理员 |
| `GET` | `/admin/review-reports` | 管理员；查看待处理举报，支持 `size` 与 `afterId` |
| `POST` | `/admin/review-reports/{reportId}/resolve` | 管理员；`KEEP` 或 `HIDE`，原因必填，重复处理返回 409 |

### 媒体

| 方法 | 路径 | 访问范围 |
| --- | --- | --- |
| `POST` | `/media` | 登录及资源归属 |
| `GET` | `/media/{mediaId}` | 按媒体授权／状态 |

发现接口由 `SHOP_SEARCH_DISCOVERY_ENABLED` 控制，启用前须完成索引校验。数据库结构以 [`V1`—`V18`](../src/main/resources/db/migration) 为准。
