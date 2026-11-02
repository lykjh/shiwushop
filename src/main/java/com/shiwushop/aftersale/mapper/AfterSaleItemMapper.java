package com.shiwushop.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.aftersale.entity.AfterSaleItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

public interface AfterSaleItemMapper extends BaseMapper<AfterSaleItem> {
    @Select("""
            SELECT COALESCE(SUM(refunded.quantity), 0)
            FROM after_sale_items refunded
            JOIN after_sale_cases sale ON sale.id = refunded.case_id
            WHERE refunded.order_item_id = #{orderItemId} AND sale.status = 'REFUNDED'
            FOR UPDATE
            """)
    long refundedQuantityForUpdate(@Param("orderItemId") Long orderItemId);
    List<AfterSaleItem> selectForCase(@Param("caseId") Long caseId);
    Integer reservedQuantity(@Param("orderItemId") Long orderItemId);
    BigDecimal reservedAmount(@Param("orderId") Long orderId);
}
