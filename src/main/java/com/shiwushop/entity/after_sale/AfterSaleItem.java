package com.shiwushop.entity.after_sale;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("after_sale_items")
public class AfterSaleItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private Long orderItemId;
    private Integer quantity;
    private BigDecimal refundAmount;
}
