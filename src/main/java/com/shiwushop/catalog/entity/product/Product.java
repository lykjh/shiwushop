package com.shiwushop.catalog.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.shiwushop.catalog.entity.status.ProductStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("products")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private Long categoryId;
    @TableField(exist = false)
    private Long brandId;
    private String name;
    private String description;
    @TableField(exist = false)
    private String coverUrl;
    private ProductStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
