package com.shiwushop.commerce.entity.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("order_items")
public class OrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long skuId;
    @TableField(exist = false)
    private Long productId;
    private String productName;
    private String skuSpec;
    private Integer quantity;
    private BigDecimal unitPrice;
}
