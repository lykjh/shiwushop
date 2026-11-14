package com.shiwushop.product.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("product_options")
public class ProductOption {
    /** 规格选项 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 规格选项名称。 */
    private String name;
    /** 排序值。 */
    private Integer sortOrder;
}
