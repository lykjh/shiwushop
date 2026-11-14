package com.shiwushop.product.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.shiwushop.product.entity.status.ProductStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("products")
public class Product {

    /** 商品 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺 ID。 */
    private Long shopId;
    /** 分类 ID。 */
    private Long categoryId;
    /** 品牌 ID；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private Long brandId;
    /** 商品名称。 */
    private String name;
    /** 展示说明。 */
    private String description;
    /** 封面图片展示地址；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String coverUrl;
    /** 商品状态，取值见 ProductStatus。 */
    private ProductStatus status;
    /** 商品内容修订号，用于上架审批和并发校验。 */
    private Long revision;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
