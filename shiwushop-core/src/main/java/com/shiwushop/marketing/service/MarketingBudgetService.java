package com.shiwushop.marketing.service;

import com.shiwushop.marketing.dto.BudgetAdjustment;
import com.shiwushop.marketing.vo.*;

import java.util.List;

/** shopId为空表示管理员入口，否则始终核对当前店主；只调整额度，不代表商家同意协议。 */
public interface MarketingBudgetService {
    List<BudgetVO> list(Long actorId, Long shopId, Long activityId);

    BudgetEntryPage entries(Long actorId, Long shopId, Long budgetId, Long afterId, int size);

    BudgetAdjustmentResult adjust(Long actorId, Long shopId, Long budgetId, String requestKey,
                                  BudgetAdjustment request);
}
