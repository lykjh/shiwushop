package com.shiwushop.trade.settlement;

import java.math.BigDecimal;
import java.util.List;

/** 结算读取售后终态与退款事实；售后侧提供只读适配。 */
public interface AfterSaleSettlementFacts {
    record Case(long id, String status, BigDecimal requestedCash, BigDecimal successfulCash,
                int unresolvedAttempts, int itemCount) {
    }

    List<Case> cases(long orderId);
}
