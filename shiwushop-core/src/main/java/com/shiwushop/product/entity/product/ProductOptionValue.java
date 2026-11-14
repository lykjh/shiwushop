package com.shiwushop.product.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("product_option_values")
public class ProductOptionValue {
    /** 规格选项值 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 商品规格选项 ID。 */
    private Long optionId;
    /** 规格选项值名称。 */
    private String valueName;
    /** 排序值。 */
    private Integer sortOrder;
}
