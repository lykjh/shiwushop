package com.shiwushop.trade.entity.cart;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cart_items")
public class CartItem {
    /** 购物车明细 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属用户 ID。 */
    private Long userId;
    /** 商品规格（SKU）ID。 */
    private Long skuId;
    /** 商品件数。 */
    private Integer quantity;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
