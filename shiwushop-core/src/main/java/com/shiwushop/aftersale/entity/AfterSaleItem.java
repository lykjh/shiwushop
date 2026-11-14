package com.shiwushop.aftersale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("after_sale_items")
public class AfterSaleItem {
    /** 售后明细 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联售后单 ID。 */
    private Long caseId;
    /** 关联订单明细 ID。 */
    private Long orderItemId;
    /** 商品件数。 */
    private Integer quantity;
    /** 本次售后申请的现金退款金额，单位元。 */
    private BigDecimal refundAmount;
    /** 本次退货单位序号区间及现金分、积分最小单位的 JSON 快照。 */
    private String unitAllocation;
    /** 退款件数的履约来源：UNSHIPPED 未发货、SHIPMENT 指定包裹、LEGACY_UNKNOWN 旧数据待核对。 */
    private String sourceKind;
    /** 退货数量对应的原发货包裹 ID。 */
    private Long sourceShipmentId;
}
