package com.shiwushop.product.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("skus")
public class Sku {

    /** SKU ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商品（SPU）ID。 */
    private Long productId;
    /** 规格选项值组合的唯一签名。 */
    private String optionSignature;
    /** 商品单价，单位元。 */
    private BigDecimal price;
    /** 普通可售库存件数。 */
    private Integer stock;
    /** SKU 状态：ACTIVE 可销售、STOPPED 停售。 */
    private String status;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
