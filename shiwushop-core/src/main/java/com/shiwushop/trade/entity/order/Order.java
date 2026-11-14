package com.shiwushop.trade.entity.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.trade.entity.status.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    /** 订单 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID。 */
    private Long userId;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 下单时店主用户 ID 快照，用于权益排除和结算归属。 */
    private Long shopOwnerUserIdAtOrder;
    /** 商品规格（SKU）ID；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private Long skuId;
    /** 商品名称；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String productName;
    /** 成交时的规格描述快照；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String skuSpec;
    /** 商品件数；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private Integer quantity;
    /** 当前采用的商品单价，单位元；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private BigDecimal unitPrice;
    /** 现金应付总额，单位元。 */
    private BigDecimal totalAmount;
    /** 按普通原价计算的商品总额，单位元。 */
    private BigDecimal originalAmount;
    /** 分配给当前对象的优惠金额，单位元。 */
    private BigDecimal discountAmount;
    /** 订单中由平台承担的优惠金额，单位元。 */
    private BigDecimal platformSubsidyAmount;
    /** 下单时冻结的会员等级代码。 */
    private String memberLevelCode;
    /** 订单价格快照的规则版本。 */
    private Integer pricingVersion;
    /** 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。 */
    private String businessType;
    /** 结算方式：CASH 现金、ZERO_CASH 零现金、POINTS 积分。 */
    private String settlementKind;
    /** 履约门禁：READY 可履约、WAIT_GROUP 等待成团、STOPPED 已停止。 */
    private String fulfillmentState;
    /** 收货人姓名；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String consignee;
    /** 手机号；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String phone;
    /** 订单收货地址文本；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String shippingAddress;
    /** 收货人姓名密文。 */
    private byte[] consigneeCiphertext;
    /** 手机号密文。 */
    private byte[] phoneCiphertext;
    /** 地址所在省份。 */
    private String province;
    /** 地址所在城市。 */
    private String city;
    /** 地址所在区县。 */
    private String district;
    /** 详细地址密文。 */
    private byte[] detailCiphertext;
    /** 收货地址快照的加密密钥版本。 */
    private Integer shippingKeyVersion;
    /** 订单支付状态：UNPAID 待支付、PAID 已支付、CANCELLED 已取消。 */
    private OrderStatus status;
    /** 订单付款截止时间。 */
    private LocalDateTime payExpiresAt;
    /** 订单取消请求时间。 */
    private LocalDateTime cancelRequestedAt;
    /** 支付或结算完成时间。 */
    private LocalDateTime paidAt;
    /** 订单全部有效商品确认收货的时间。 */
    private LocalDateTime fullyReceivedAt;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
