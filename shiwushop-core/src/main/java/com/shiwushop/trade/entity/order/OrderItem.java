package com.shiwushop.trade.entity.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("order_items")
public class OrderItem {
    /** 订单明细 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 商品规格（SKU）ID。 */
    private Long skuId;
    /** 商品（SPU）ID；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private Long productId;
    /** 下单时冻结的商品名称。 */
    private String productName;
    /** 成交时的规格描述快照。 */
    private String skuSpec;
    /** 商品件数。 */
    private Integer quantity;
    /** 成交单价快照，不随当前商品价格变化，单位元。 */
    private BigDecimal unitPrice;
    /** 优惠前的普通单价快照，单位元。 */
    private BigDecimal originalUnitPrice;
    /** 分配给当前对象的优惠金额，单位元。 */
    private BigDecimal discountAmount;
    /** 扣除优惠后的应付金额，单位元。 */
    private BigDecimal payableAmount;
    /** 订单明细类型：SALE 销售、GIFT 赠品、REDEEM 积分兑换。 */
    private String itemKind;
    /** 产生该订单明细的活动 ID。 */
    private Long sourceActivityId;
}
