package com.shiwushop.commerce.mapper.after_sale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.commerce.entity.after_sale.AfterSaleItem;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

public interface AfterSaleItemMapper extends BaseMapper<AfterSaleItem> {
    List<AfterSaleItem> selectForCase(@Param("caseId") Long caseId);
    Integer reservedQuantity(@Param("orderItemId") Long orderItemId);
    BigDecimal reservedAmount(@Param("orderId") Long orderId);
}
