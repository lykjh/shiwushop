package com.shiwushop.marketing.vo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record BudgetAdjustmentResult(
    /** 调整后的预算信息。 */
    @JsonUnwrapped BudgetVO budget,
    /** 本次预算调整流水 ID。 */
    Long budgetEntryId
) {
}
