package com.shiwushop.aftersale.mapper;

import com.shiwushop.trade.settlement.AfterSaleSettlementFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcAfterSaleSettlementFacts implements AfterSaleSettlementFacts {
    private final JdbcTemplate jdbc;

    @Override
    public List<Case> cases(long orderId) {
        return jdbc.query("""
                SELECT c.id,c.status,c.refund_amount,
                       COALESCE((SELECT SUM(r.amount) FROM refund_attempts r
                           WHERE r.case_id=c.id AND r.status='SUCCESS'),0),
                       (SELECT COUNT(*) FROM refund_attempts r WHERE r.case_id=c.id
                           AND r.status IN ('PENDING','UNKNOWN')),
                       (SELECT COUNT(*) FROM after_sale_items ai WHERE ai.case_id=c.id)
                FROM after_sale_cases c WHERE c.order_id=? ORDER BY c.id
                """, (rs, row) -> new Case(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getInt(5), rs.getInt(6)), orderId);
    }
}
