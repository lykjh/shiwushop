package com.shiwushop.marketing.service;

import java.math.BigDecimal;
import java.util.List;

/** 成交时冻结的营销承担和已执行冲回；不按现行活动规则重新报价。 */
public interface MarketingSettlementFacts {
    record Totals(BigDecimal cashDiscount, BigDecimal cashPlatform, BigDecimal cashMerchant,
                  BigDecimal costPlatform, int costRows, int giftCostRows, int pointCostRows,
                  int invalidCostRows) {
    }

    record Reversal(long caseId, BigDecimal baseCash, BigDecimal cashPlatform,
                    BigDecimal costPlatform, int baseRows, int itemRows, int pendingRows) {
    }

    record Snapshot(Totals totals, List<Reversal> reversals) {
    }

    Snapshot snapshot(long orderId);
}
