package com.shiwushop.product.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_images")
public class ProductImage {
    /** 商品图片记录 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 媒体资源 ID。 */
    private Long mediaId;
    /** 是否为封面图片，1 是、0 否。 */
    private Integer isCover;
    /** 图片展示地址；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String imageUrl;
    /** 排序值。 */
    private Integer sortOrder;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
}
