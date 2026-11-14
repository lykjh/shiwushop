package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.service.MarketingSettlementFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;


@Repository
@RequiredArgsConstructor
public class JdbcMarketingSettlementFacts implements MarketingSettlementFacts {
    private final JdbcTemplate jdbc;

    @Override
    public Snapshot snapshot(long orderId) {
        Totals totals = jdbc.queryForObject("""
                SELECT COALESCE(SUM(IF(a.benefit_kind NOT IN ('GIFT_COST','POINTS_COST'),a.amount,0)),0),
                       COALESCE(SUM(IF(a.benefit_kind NOT IN ('GIFT_COST','POINTS_COST'),a.platform_amount,0)),0),
                       COALESCE(SUM(IF(a.benefit_kind NOT IN ('GIFT_COST','POINTS_COST'),a.merchant_amount,0)),0),
                       COALESCE(SUM(IF(a.benefit_kind IN ('GIFT_COST','POINTS_COST'),a.platform_amount,0)),0),
                       COALESCE(SUM(a.benefit_kind IN ('GIFT_COST','POINTS_COST')),0),
                       COALESCE(SUM(a.benefit_kind='GIFT_COST'),0),
                       COALESCE(SUM(a.benefit_kind='POINTS_COST'),0),
                       COALESCE(SUM(a.benefit_kind IN ('GIFT_COST','POINTS_COST') AND
                           (a.unit_cost_amount IS NULL OR a.unit_cost_amount*i.quantity<>a.amount)),0)
                FROM order_promotion_allocations a JOIN order_items i ON i.id=a.order_item_id
                WHERE i.order_id=?
                """, (rs, row) -> new Totals(rs.getBigDecimal(1), rs.getBigDecimal(2),
                rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getInt(5), rs.getInt(6),
                rs.getInt(7), rs.getInt(8)), orderId);
        var reversals = jdbc.query("""
                SELECT c.id,
                       COALESCE(SUM(IF(a.benefit_key='BASE',a.cash_refund_amount,0)),0),
                       COALESCE(SUM(IF(p.benefit_kind NOT IN ('GIFT_COST','POINTS_COST'),
                           a.platform_reversal_amount,0)),0),
                       COALESCE(SUM(IF(p.benefit_kind IN ('GIFT_COST','POINTS_COST'),
                           a.platform_reversal_amount,0)),0),
                       COALESCE(SUM(a.benefit_key='BASE'),0),COUNT(DISTINCT ai.id),
                       COALESCE(SUM(a.status='PENDING'),0)
                FROM after_sale_cases c
                LEFT JOIN after_sale_items ai ON ai.case_id=c.id
                LEFT JOIN marketing_refund_allocations a ON a.after_sale_item_id=ai.id
                LEFT JOIN order_promotion_allocations p ON p.id=a.promotion_allocation_id
                WHERE c.order_id=? GROUP BY c.id ORDER BY c.id
                """, (rs, row) -> new Reversal(rs.getLong(1), rs.getBigDecimal(2),
                rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)), orderId);
        return new Snapshot(totals, reversals);
    }
}
