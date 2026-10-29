package com.shiwushop.catalog.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("product_option_values")
public class ProductOptionValue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long optionId;
    private String valueName;
    private Integer sortOrder;
}
