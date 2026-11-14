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

    @org.apache.ibatis.annotations.Select("""
            SELECT i.unit_allocation FROM after_sale_items i JOIN after_sale_cases c ON c.id=i.case_id
            WHERE i.order_item_id=#{item} AND c.status NOT IN ('REJECTED','CLOSED') ORDER BY i.id
            """)
    List<String> occupiedUnits(long item);

    Integer reservedQuantity(@Param("orderItemId") Long orderItemId);

    @Select("SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai "
            + "JOIN after_sale_cases c ON c.id=ai.case_id AND c.status NOT IN ('REJECTED','CLOSED') "
            + "WHERE ai.order_item_id=#{orderItemId} AND ai.source_kind=#{sourceKind} "
            + "AND (#{shipmentId} IS NULL OR ai.source_shipment_id=#{shipmentId})")
    int reservedQuantityForSource(@Param("orderItemId") Long orderItemId,
                                  @Param("sourceKind") String sourceKind,
                                  @Param("shipmentId") Long shipmentId);

    BigDecimal reservedAmount(@Param("orderId") Long orderId);
}
