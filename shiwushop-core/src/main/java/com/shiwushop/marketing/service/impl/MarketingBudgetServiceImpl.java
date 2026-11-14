package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.dto.BudgetAdjustment;
import com.shiwushop.marketing.mapper.MarketingBudgetMapper;
import com.shiwushop.marketing.model.MarketingBudget;
import com.shiwushop.marketing.model.MarketingBudgetEntry;
import com.shiwushop.marketing.service.MarketingBudgetService;
import com.shiwushop.marketing.vo.*;
import com.shiwushop.merchant.service.MerchantShopAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketingBudgetServiceImpl implements MarketingBudgetService {
    private final MarketingBudgetMapper mapper;
    private final AccountSecurity security;
    private final MerchantShopAccess shops;
    private final MarketingSettings settings;
    private final ObjectMapper json;
    private final Clock businessClock;

    private void access(Long actor, Long shopId, boolean write) {
        if (shopId == null)
            security.requireAdmin(actor);
        else if (write)
            shops.requireOwnedForUpdate(actor, shopId, "店铺不存在");
        else
            shops.requireOwned(actor, shopId, "店铺不存在");
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }

    private MarketingBudget requireBudget(Long id, Long shopId, boolean lock) {
        var budget = lock ? mapper.lock(id) : mapper.find(id);
        if (budget == null || (shopId != null && !shopId.equals(budget.shopId())))
            throw new BusinessException(404, "预算不存在");
        return budget;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetVO> list(Long actorId, Long shopId, Long activityId) {
        access(actorId, shopId, false);
        var budgets = mapper.list(activityId, shopId);
        if (shopId == null ? !mapper.activityExists(activityId) : budgets.isEmpty())
            throw new BusinessException(404, "活动不存在");
        return budgets.stream().map(BudgetVO::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetEntryPage entries(Long actorId, Long shopId, Long budgetId, Long afterId, int size) {
        access(actorId, shopId, false);
        requireBudget(budgetId, shopId, false);
        var rows = mapper.entries(budgetId, afterId, size + 1);
        boolean more = rows.size() > size;
        var page = rows.stream().limit(size).map(BudgetEntryVO::from).toList();
        return new BudgetEntryPage(page, more, more ? page.getLast().id() : null);
    }

    @Override
    @Transactional
    public BudgetAdjustmentResult adjust(Long actorId, Long shopId, Long budgetId, String requestKey,
                                         BudgetAdjustment request) {
        access(actorId, shopId, true);
        var budget = requireBudget(budgetId, shopId, true);
        if (request.deltaAmount().signum() == 0)
            throw new BusinessException(400, "预算调整不能为零", Map.of("reason", "INVALID_ARGUMENT"), null);
        String key = "BUDGET_ADJUST:" + actorId + ":" + budgetId + ":" + requestKey;
        var previous = mapper.operation(budgetId, key);
        if (previous != null) {
            if (previous.getBudgetDeltaAmount().compareTo(request.deltaAmount()) != 0
                    || !previous.getReason().equals(request.reason()) ||
                    !previous.getEvidenceReference().equals(request.evidenceRef()))
                throw new BusinessException(409, "幂等键对应的预算调整内容不同",
                        Map.of("reason", "IDEMPOTENCY_CONFLICT"), null);
            return new BudgetAdjustmentResult(BudgetVO.from(budget), previous.getId());
        }
        // 人工减额只能收回未承诺余额，不能侵占已占资金或改写已发生支出。
        var amount = budget.budgetAmount().add(request.deltaAmount());
        if (amount.compareTo(budget.heldAmount().add(budget.spentAmount())) < 0)
            throw new BusinessException(409, "预算不能小于已有承诺及支出", Map.of("reason", "BUDGET_NOT_ENOUGH"), null);
        if (amount.compareTo(new BigDecimal("9999999999999999.99")) > 0)
            throw new BusinessException(409, "预算总额超过金额上限", Map.of("reason", "INVALID_STATE"), null);
        var entry = new MarketingBudgetEntry();
        entry.setBudgetId(budgetId);
        entry.setOperationKey(key);
        entry.setActorUserId(actorId);
        entry.setReason(request.reason());
        entry.setEvidenceReference(request.evidenceRef());
        entry.setBudgetDeltaAmount(request.deltaAmount());
        entry.setBudgetAfter(amount);
        entry.setHeldAfter(budget.heldAmount());
        entry.setSpentAfter(budget.spentAmount());
        var now = LocalDateTime.now(businessClock);
        entry.setCreatedAt(now);
        // 总额、调整流水与审计同事务提交，避免金额改变却没有责任凭据。
        mapper.updateAmount(budgetId, amount);
        mapper.insertAdjustment(entry);
        mapper.audit(actorId, budgetId, json.writeValueAsString(Map.of("entryId", entry.getId(),
                "ledger", "marketing_budget_entries", "reasonField", "reason", "evidenceField", "evidence_reference",
                "deltaAmount", request.deltaAmount(), "budgetAfter", amount)), now);
        return new BudgetAdjustmentResult(BudgetVO.from(mapper.find(budgetId)), entry.getId());
    }
}
