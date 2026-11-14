package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.*;
import com.shiwushop.marketing.dto.*;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import com.shiwushop.marketing.mapper.MarketingBudgetMapper;
import com.shiwushop.marketing.model.*;
import com.shiwushop.marketing.service.MarketingActivityService;
import com.shiwushop.marketing.vo.*;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.product.service.ProductArchiveConstraint;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.member.service.MemberAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketingActivityServiceImpl implements MarketingActivityService, ProductArchiveConstraint {
    private final MarketingActivityMapper mapper;
    private final MarketingBudgetMapper budgets;
    private final AccountSecurity security;
    private final MerchantShopAccess access;
    private final ShopService shops;
    private final TradingProductService products;
    private final MemberAccountService members;
    private final MarketingSettings settings;
    private final ActivityContentHash hash;
    private final ObjectMapper json;
    private final Clock businessClock;

    /** 商品归档调用方已锁店铺及商品；逐项当前读保留活动、参与和退款责任。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasUnfinishedProductCommitments(Long shopId, Long productId) {
        return mapper.activeProductActivity(shopId, productId) != null
                || mapper.unfinishedProductParticipation(shopId, productId) != null
                || mapper.unfinishedProductGroup(shopId, productId) != null
                || mapper.productStockPoolBalance(shopId, productId) != null
                || mapper.pendingProductRefund(shopId, productId) != null;
    }

    private void authorize(Long actor, Long shop) {
        if (shop == null)
            security.requireAdmin(actor);
        else
            access.requireOwned(actor, shop, "店铺不存在");
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }

    private MarketingActivity require(Long id) {
        var row = mapper.lock(id);
        if (row == null)
            throw new BusinessException(404, "活动不存在");
        return row;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(businessClock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value.atOffset(ZoneOffset.ofHours(8));
    }

    private static void conflict(String reason, String message) {
        throw new BusinessException(409, message, Map.of("reason", reason), null);
    }

    private static void invalid(String message) {
        throw new BusinessException(400, message, Map.of("reason", "INVALID_ARGUMENT"), null);
    }

    private record Content(ActivityAdminVO full, List<MarketingBudget> budgets) {
    }

    private Content content(MarketingActivity row) {
        var scopes = mapper.scopes(row.getId());
        var catalog = products.cartProducts(
                scopes.stream().collect(Collectors.toMap(ActivityScope::getSkuId, s -> 1, (a, b) -> a)));
        var views = scopes.stream().map(s -> new ActivityAdminVO.Scope(s.getId(),
                catalog.get(s.getSkuId()).product().getShopId(), s.draft())).toList();
        var funds = mapper.lockBudgets(row.getId());
        var owners = shops.findByIds(funds.stream().map(MarketingBudget::shopId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(s -> s.getId(), s -> s.getOwnerUserId()));
        boolean agreed = mapper.agreements(row.getId()).stream().allMatch(a -> a.agreedByUserId() != null
                && a.agreedByUserId().equals(owners.get(a.shopId())));
        var coupon = mapper.coupon(row.getId());
        return new Content(new ActivityAdminVO(row.getId(), row.getActivityCode(), row.getVersionNo(), row.getStatus(),
                row.getName(), row.getActivityType(), offset(row.getStartAt()), offset(row.getEndAt()),
                Arrays.asList(json.readValue(row.getEligibleLevelCodes(), String[].class)), row.getRulesText(), views,
                mapper.tiers(row.getId()), coupon == null ? null : coupon.draft(),
                funds.stream()
                        .map(b -> new BudgetProposal(b.party(), b.shopId(), b.budgetAmount(), b.platformShareBp()))
                        .toList(),
                offset(row.getCreatedAt()), row.getReviewedAt() == null ? null : offset(row.getReviewedAt()),
                row.getReviewReason(), agreed), funds);
    }

    private ActivityAdminVO.Version view(Content content, Long shop) {
        var full = content.full();
        String etag = hash.etag(content);
        if (shop == null)
            return new ActivityAdminVO.Version(full, etag);
        var ownScopes = full.scopes().stream().filter(s -> shop.equals(s.shopId())).toList();
        if (ownScopes.isEmpty())
            throw new BusinessException(404, "活动不存在");
        var ownSkuIds = ownScopes.stream().map(s -> s.draft().skuId()).collect(Collectors.toSet());
        return new ActivityAdminVO.Version(
                new ActivityAdminVO(full.id(), full.activityCode(), full.versionNo(), full.status(),
                        full.name(), full.type(), full.startAt(), full.endAt(), full.eligibleLevelCodes(),
                        full.rulesText(), ownScopes,
                        full.tiers().stream().filter(t -> t.giftSkuId() == null || ownSkuIds.contains(t.giftSkuId()))
                                .toList(), full.couponRule(),
                        full.budgetProposals().stream().filter(b -> shop.equals(b.shopId())).toList(), full.createdAt(),
                        full.reviewedAt(),
                        full.reviewReason(), full.agreementsComplete()), etag);
    }

    /** ETag 覆盖完整活动内容，防止按过时草稿编辑、确认承担或审批。 */
    private void match(Content content, String etag) {
        if (!hash.etag(content).equals(etag))
            conflict("EDIT_CONFLICT", "活动内容已变化，请重新读取");
    }

    private void editableBy(MarketingActivity row, Long shop) {
        if (shop != null &&
                !(row.getVersionNo() == 1 ? row.getRequestScope() : mapper.initialScope(row.getActivityCode())).equals(
                        "CREATE_SHOP:" + shop))
            throw new BusinessException(403, "参与店主不能编辑平台活动");
    }

    @Override
    @Transactional
    public ActivityAdminVO.Version detail(Long actor, Long shop, Long id) {
        authorize(actor, shop);
        return view(content(require(id)), shop);
    }

    @Override
    @Transactional
    public ActivityAdminVO.Page list(Long actor, Long shop, Long afterId, int size, String type, String status,
                                     String code) {
        authorize(actor, shop);
        var rows = mapper.list(shop, afterId, type, status, code, size + 1);
        boolean more = rows.size() > size;
        // 与写入锁顺序一致；输出仍按ID倒序，避免两个列表逆序锁住相同活动。
        var selected = rows.stream().limit(size).sorted(Comparator.comparing(MarketingActivity::getId)).toList();
        var items = new ArrayList<ActivityAdminVO>();
        for (var row : selected)
            items.add(view(content(require(row.getId())), shop).activity());
        Collections.reverse(items);
        return new ActivityAdminVO.Page(items, more, more ? items.getLast().id() : null);
    }

    private Map<Long, Sku> lockResources(Long actor, Long shop, ActivityDraft draft) {
        var quantities = draft.scopes().stream().collect(Collectors.toMap(ScopeDraft::skuId, s -> 1, (a, b) -> a));
        var catalog = products.cartProducts(quantities);
        var participating = new TreeSet<Long>();
        for (long skuId : quantities.keySet()) {
            var item = catalog.get(skuId);
            if (item == null || item.sku() == null || item.product() == null)
                invalid("活动商品不存在");
            participating.add(item.product().getShopId());
        }
        if (shop != null && !participating.equals(Set.of(shop)))
            throw new BusinessException(403, "商家活动只能包含本店商品");
        for (long shopId : participating) {
            if (shop != null)
                access.requireOwnedForUpdate(actor, shopId, "店铺不存在");
            else
                shops.lockById(shopId);
        }
        var locked = new TreeMap<Long, Sku>();
        for (long skuId : new TreeSet<>(quantities.keySet()))
            locked.put(skuId, products.lockSku(skuId));
        var proposed = draft.budgetProposals().stream().filter(b -> b.shopId() != null).map(BudgetProposal::shopId)
                .collect(Collectors.toSet());
        if (!proposed.equals(participating))
            invalid("预算方案必须与参与店铺逐一对应");
        boolean platform = draft.budgetProposals().stream().anyMatch(b -> b.party().equals("PLATFORM"));
        if (shop != null && (platform || draft.budgetProposals().stream().anyMatch(b -> b.platformShareBp() > 0)))
            throw new BusinessException(403, "商家不能授权平台预算");
        if (!platform && draft.budgetProposals().stream().anyMatch(b -> b.platformShareBp() > 0))
            invalid("平台分摊必须配置平台预算");
        for (var scope : draft.scopes()) {
            var sku = locked.get(scope.skuId());
            if (scope.memberPrices() != null && sku.getPrice().compareTo(scope.memberPrices().silver()) < 0)
                invalid("会员价格不能高于普通售价");
        }
        if (draft.type().equals("FULL_GIFT") && participating.size() != 1)
            invalid("满赠销售与赠品必须属于同一店铺");
        if (draft.couponRule() != null && draft.couponRule().grantMode().equals("MEMBERSHIP")) {
            var rule = draft.couponRule();
            var level = members.levels().stream().filter(l -> l.code().equals(rule.memberLevelCode())).findFirst()
                    .orElseThrow();
            if (level.monthlyCouponCount() != rule.perUserLimit() ||
                    level.monthlyCouponFaceAmount().compareTo(rule.faceAmount()) != 0
                    || level.monthlyCouponThresholdAmount().compareTo(rule.thresholdAmount()) != 0)
                invalid("月券参数不符合等级权益");
        }
        return locked;
    }

    private MarketingActivity row(Long actor, Long shop, String key, ActivityDraft draft) {
        var row = new MarketingActivity();
        row.setActivityCode(UUID.randomUUID().toString());
        row.setVersionNo(1);
        row.setStatus("DRAFT");
        row.setActivityType(draft.type());
        row.setCreatorUserId(actor);
        row.setRequestKey(key);
        row.setRequestScope(shop == null ? "CREATE_PLATFORM" : "CREATE_SHOP:" + shop);
        row.setRequestHash(hash.hash(draft));
        row.setCreatedAt(now());
        setDraft(row, draft);
        return row;
    }

    private void setDraft(MarketingActivity row, ActivityDraft draft) {
        row.setName(draft.name());
        row.setRulesText(draft.rulesText());
        row.setStartAt(ActivityDraftRules.time(draft.startAt()));
        row.setEndAt(ActivityDraftRules.time(draft.endAt()));
        row.setEligibleLevelCodes(json.writeValueAsString(draft.eligibleLevelCodes()));
        row.setUpdatedAt(now());
    }

    private void writeRules(Long id, ActivityDraft draft) {
        var gifts = new HashMap<Long, Long>();
        for (var scope : draft.scopes()) {
            var inserted = new ActivityScope.Insert(id, scope);
            mapper.insertScope(inserted);
            if (scope.memberPrices() != null)
                mapper.prices(inserted.getId(), scope.memberPrices());
            if (scope.scopeRole().equals("GIFT"))
                gifts.put(scope.skuId(), inserted.getId());
        }
        for (var tier : draft.tiers())
            mapper.insertTier(id, tier, gifts.get(tier.giftSkuId()));
        var coupon = draft.couponRule();
        if (coupon != null)
            mapper.insertCoupon(id, coupon, ActivityDraftRules.time(coupon.claimEndAt()),
                    coupon.fixedExpireAt() == null ? null : ActivityDraftRules.time(coupon.fixedExpireAt()));
    }

    @Override
    @Transactional
    public ActivityAdminVO.Version create(Long actor, Long shop, String key, ActivityDraft draft) {
        authorize(actor, shop);
        if (shop != null)
            access.requireOwnedForUpdate(actor, shop, "店铺不存在");
        var priorId = mapper.requestId(actor, shop == null ? "CREATE_PLATFORM" : "CREATE_SHOP:" + shop, key);
        if (priorId != null) {
            var previous = require(priorId);
            if (!Arrays.equals(hash.hash(draft), previous.getRequestHash()))
                conflict("IDEMPOTENCY_CONFLICT", "原请求键对应不同草稿");
            return view(content(previous), shop);
        }
        ActivityDraftRules.validate(draft);
        lockResources(actor, shop, draft);
        var inserted = row(actor, shop, key, draft);
        mapper.insert(inserted);
        var saved = require(inserted.getId());
        if (!Arrays.equals(inserted.getRequestHash(), saved.getRequestHash()))
            conflict("IDEMPOTENCY_CONFLICT", "原请求键对应不同草稿");
        if (saved.getActivityCode().equals(inserted.getActivityCode())) {
            writeRules(saved.getId(), draft);
            for (var proposal : draft.budgetProposals())
                mapper.insertBudget(saved.getId(), proposal);
            mapper.audit(actor, saved.getId(), "ACTIVITY_CREATED", "创建活动草稿", now());
        }
        return view(content(saved), shop);
    }

    @Override
    @Transactional
    public ActivityAdminVO.Version edit(Long actor, Long shop, Long id, String etag, ActivityDraft draft) {
        authorize(actor, shop);
        ActivityDraftRules.validate(draft);
        lockResources(actor, shop, draft);
        var row = require(id);
        editableBy(row, shop);
        var original = content(row);
        view(original, shop);
        match(original, etag);
        if (!row.getStatus().equals("DRAFT"))
            conflict("INVALID_STATE", "只能编辑草稿");
        if (!row.getActivityType().equals(draft.type()))
            invalid("同一版本不能改变活动类型");
        replaceBudgets(id, original.budgets(), draft.budgetProposals());
        mapper.deleteTiers(id);
        mapper.deletePrices(id);
        mapper.deleteScopes(id);
        mapper.deleteCoupon(id);
        setDraft(row, draft);
        mapper.updateDraft(row);
        writeRules(id, draft);
        mapper.audit(actor, id, "ACTIVITY_DRAFT_EDITED", "全量编辑草稿并使参与协议失效", now());
        return view(content(row), shop);
    }

    /** 有流水的预算保留原 ID，额度另走调整接口，避免切断责任与审计链。 */
    private void replaceBudgets(Long id, List<MarketingBudget> existing, List<BudgetProposal> proposals) {
        var remaining = new LinkedHashMap<Long, BudgetProposal>();
        for (var proposal : proposals)
            remaining.put(proposal.shopId() == null ? 0L : proposal.shopId(), proposal);
        for (var budget : existing) {
            var proposal = remaining.remove(budget.shopId() == null ? 0L : budget.shopId());
            boolean hasEntries = mapper.firstEntry(budget.id()) != null;
            if (proposal == null) {
                if (hasEntries)
                    conflict("INVALID_STATE", "已有流水的预算责任方不能从草稿删除");
                mapper.deleteBudget(budget.id());
            } else {
                if (hasEntries && proposal.budgetAmount().compareTo(budget.budgetAmount()) != 0)
                    conflict("INVALID_STATE", "已有流水的预算额度须通过调整接口修改");
                mapper.editBudget(budget.id(), proposal);
            }
        }
        for (var proposal : remaining.values())
            mapper.insertBudget(id, proposal);
    }

    @Override
    @Transactional
    public BudgetVO agree(Long actor, Long shop, Long id, String etag, ActivityAgreement request) {
        authorize(actor, shop);
        access.requireOwnedForUpdate(actor, shop, "店铺不存在");
        var row = require(id);
        var content = content(row);
        view(content, shop);
        var budget = content.budgets().stream().filter(b -> shop.equals(b.shopId())).findFirst().orElseThrow();
        if (!request.platformShareBp().equals(budget.platformShareBp()) ||
                request.merchantBudgetAmount().compareTo(budget.budgetAmount()) != 0)
            conflict("EDIT_CONFLICT", "协议内容与当前草案不一致");
        String action = "ACTIVITY_AGREED_SHOP:" + shop;
        if (budget.agreedAt() != null && sameAudit(id, action, actor, request.reason()))
            return BudgetVO.from(budget);
        match(content, etag);
        if (!row.getStatus().equals("DRAFT"))
            conflict("INVALID_STATE", "仅草稿可以确认参与协议");
        mapper.agree(budget.id(), actor, now());
        mapper.audit(actor, id, action, request.reason(), now());
        return BudgetVO.from(budgets.find(budget.id()));
    }

    @Override
    @Transactional
    public ActivityAdminVO.Version submit(Long actor, Long shop, Long id, String etag) {
        authorize(actor, shop);
        if (shop != null)
            access.requireOwnedForUpdate(actor, shop, "店铺不存在");
        var row = require(id);
        editableBy(row, shop);
        var current = content(row);
        view(current, shop);
        if (row.getStatus().equals("PENDING_REVIEW") &&
                sameAudit(id, "ACTIVITY_SUBMITTED_FROM_DRAFT", actor, "提交审批"))
            return view(current, shop);
        match(current, etag);
        if (!row.getStatus().equals("DRAFT"))
            conflict("INVALID_STATE", "仅草稿可以提交审批");
        if (!row.getEndAt().isAfter(now()))
            conflict("INVALID_STATE", "活动已结束");
        if (!current.full().agreementsComplete())
            conflict("AGREEMENT_REQUIRED", "参与店铺尚未确认最新协议");
        mapper.status(id, "PENDING_REVIEW", now());
        row.setStatus("PENDING_REVIEW");
        mapper.audit(actor, id, "ACTIVITY_SUBMITTED_FROM_DRAFT", "提交审批", now());
        return view(content(row), shop);
    }

    @Override
    @Transactional
    public ActivityAdminVO.Version decide(Long actor, Long id, String etag, ActivityDecision request) {
        authorize(actor, null);
        // 同等级审批先串行化再查区间，防止并发批准两个重叠的月券模板。
        String monthlyLevel = mapper.monthlyLevel(id);
        if (monthlyLevel != null)
            members.lockLevel(monthlyLevel);
        var lockedOwners = new HashMap<Long, Long>();
        for (long shopId : budgets.list(id, null).stream().map(MarketingBudget::shopId).filter(Objects::nonNull)
                .sorted().toList())
            lockedOwners.put(shopId, shops.lockById(shopId).getOwnerUserId());
        var lockedSkus = new TreeMap<Long, Sku>();
        for (long skuId : mapper.skuIds(id))
            lockedSkus.put(skuId, products.lockSku(skuId));
        var row = require(id);
        var current = content(row);
        String target = request.decision().equals("APPROVE") ? "APPROVED" : "REJECTED";
        if (row.getCreatorUserId().equals(actor))
            throw new BusinessException(403, "创建者不能审批自己的活动");
        if (row.getStatus().equals(target)) {
            if (actor.equals(row.getReviewerUserId()) && request.reason().equals(row.getReviewReason()))
                return view(current, null);
            conflict("INVALID_STATE", "已完成不同的审批决定");
        }
        match(current, etag);
        if (!row.getStatus().equals("PENDING_REVIEW"))
            conflict("INVALID_STATE", "活动未处于待审批状态");
        if (request.decision().equals("APPROVE")) {
            if (!row.getEndAt().isAfter(now()))
                conflict("INVALID_STATE", "活动已结束");
            if (mapper.agreements(id).stream().anyMatch(
                    a -> a.agreedByUserId() == null || !a.agreedByUserId().equals(lockedOwners.get(a.shopId()))))
                conflict("AGREEMENT_REQUIRED", "参与协议不完整或店主已变更");
            var rule = current.full().couponRule();
            if (rule != null && rule.grantMode().equals("MEMBERSHIP")) {
                if (!rule.memberLevelCode().equals(monthlyLevel))
                    conflict("EDIT_CONFLICT", "月券等级配置已变化");
                if (mapper.overlappingMonthly(id, monthlyLevel, row.getStartAt(),
                        ActivityDraftRules.time(rule.claimEndAt())) != null)
                    conflict("ACTIVITY_OVERLAP", "同等级月券领取区间重叠");
            }
            for (var scope : current.full().scopes()) {
                var sku = lockedSkus.get(scope.draft().skuId());
                if (sku == null)
                    conflict("EDIT_CONFLICT", "活动商品范围已改变");
                if (scope.draft().memberPrices() != null &&
                        sku.getPrice().compareTo(scope.draft().memberPrices().silver()) < 0)
                    conflict("INVALID_STATE", "普通售价变化后会员价格不合法");
            }
            if (Set.of("MEMBER_PRICE", "LIMITED_PRICE", "FLASH", "GROUP", "BARGAIN").contains(row.getActivityType())
                    && mapper.overlapping(id, row.getActivityType(), row.getStartAt(), row.getEndAt(),
                    new ArrayList<>(lockedSkus.keySet())) != null)
                conflict("ACTIVITY_OVERLAP", "同SKU同类价格活动时间重叠");
        }
        mapper.decision(id, target, actor, request.reason(), now());
        mapper.audit(actor, id, "ACTIVITY_" + target + "_FROM_PENDING_REVIEW", request.reason(), now());
        return view(content(require(id)), null);
    }

    private boolean sameAudit(Long id, String action, Long actor, String reason) {
        var audit = mapper.lastAudit(id, action);
        return audit != null && actor.equals(audit.actorUserId()) && reason.equals(audit.summary());
    }

    @Override
    @Transactional
    public ActivityAdminVO.Version transition(Long actor, Long id, String etag, String command,
                                              ActivityReason request) {
        authorize(actor, null);
        var row = require(id);
        var current = content(row);
        String target = switch (command) {
            case "pause" -> "PAUSED";
            case "resume" -> "RUNNING";
            case "terminate" -> "TERMINATED";
            default -> throw new IllegalArgumentException("未知活动命令：" + command);
        };
        String action = "ACTIVITY_" + command.toUpperCase(Locale.ROOT) + "_FROM_";
        if (row.getStatus().equals(target)) {
            var sources = command.equals("terminate") ? List.of("APPROVED", "RUNNING", "PAUSED")
                    : List.of(command.equals("pause") ? "RUNNING" : "PAUSED");
            if (sources.stream().anyMatch(s -> sameAudit(id, action + s, actor, request.reason())))
                return view(current, null);
            conflict("INVALID_STATE", "当前状态对应不同操作");
        }
        match(current, etag);
        boolean valid = switch (command) {
            case "pause" -> row.getStatus().equals("RUNNING");
            case "resume" -> row.getStatus().equals("PAUSED") && row.getEndAt().isAfter(now());
            default -> Set.of("APPROVED", "RUNNING", "PAUSED").contains(row.getStatus());
        };
        if (!valid)
            conflict("INVALID_STATE", "当前状态不能执行该操作");
        mapper.status(id, target, now());
        mapper.audit(actor, id, action + row.getStatus(), request.reason(), now());
        return view(content(require(id)), null);
    }

    @Override
    @Transactional
    public ActivityAdminVO.Version revise(Long actor, Long shop, Long id, String key, ActivityReason request) {
        authorize(actor, shop);
        if (shop != null)
            access.requireOwnedForUpdate(actor, shop, "店铺不存在");
        var source = mapper.find(id);
        if (source == null)
            throw new BusinessException(404, "活动不存在");
        // 锁同一活动首版来分配版本号，旧版及其已承诺权益保持原样。
        var first = mapper.lockFirstVersion(source.getActivityCode());
        editableBy(first, shop);
        source = require(id);
        var original = content(source);
        view(original, shop);
        String scope = "VERSION:" + id;
        var previous = mapper.request(actor, scope, key);
        if (previous != null) {
            if (!Arrays.equals(previous.getRequestHash(), hash.hash(request)))
                conflict("IDEMPOTENCY_CONFLICT", "版本请求键对应不同原因");
            return view(content(previous), shop);
        }
        if (!Set.of("APPROVED", "RUNNING", "PAUSED", "ENDED", "TERMINATED", "REJECTED").contains(source.getStatus()))
            conflict("INVALID_STATE", "只有已审批或驳回版本可以修订");
        var full = original.full();
        var draft = new ActivityDraft(full.name(), full.type(), full.startAt(), full.endAt(), full.eligibleLevelCodes(),
                full.rulesText(),
                full.scopes().stream().map(ActivityAdminVO.Scope::draft).toList(), full.tiers(), full.couponRule(),
                full.budgetProposals());
        var next = row(actor, shop, key, draft);
        next.setActivityCode(source.getActivityCode());
        next.setVersionNo(mapper.lastVersion(source.getActivityCode()) + 1);
        next.setSupersedesId(id);
        next.setRequestScope(scope);
        next.setRequestHash(hash.hash(request));
        mapper.insert(next);
        writeRules(next.getId(), draft);
        for (var proposal : draft.budgetProposals())
            mapper.insertBudget(next.getId(), proposal);
        mapper.audit(actor, next.getId(), "ACTIVITY_VERSION_CREATED", request.reason(), now());
        return view(content(require(next.getId())), shop);
    }

    @Override
    public List<Long> due() {
        return settings.enabled() ? mapper.due(now()) : List.of();
    }

    @Override
    @Transactional
    public void advance(Long id) {
        var row = require(id);
        var now = now();
        String target = null;
        if (Set.of("APPROVED", "RUNNING", "PAUSED").contains(row.getStatus()) && !row.getEndAt().isAfter(now))
            target = "ENDED";
        else if (row.getStatus().equals("APPROVED") && !row.getStartAt().isAfter(now))
            target = "RUNNING";
        if (target != null) {
            mapper.status(id, target, now);
            mapper.audit(null, id, "ACTIVITY_" + target + "_FROM_" + row.getStatus(), "服务端时间推进", now);
        }
    }
}
