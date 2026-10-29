package com.shiwushop.catalog.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_images")
public class ProductImage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private Long mediaId;
    private Integer isCover;
    @TableField(exist = false)
    private String imageUrl;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
