package com.shiwushop.catalog.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("skus")
public class Sku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;
    @TableField(exist = false)
    private String spec;
    private String optionSignature;
    @TableField(exist = false)
    private Integer legacy;
    private BigDecimal price;
    private Integer stock;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
