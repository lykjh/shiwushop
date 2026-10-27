package com.shiwushop.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("product_options")
public class ProductOption {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private String name;
    private Integer sortOrder;
}
