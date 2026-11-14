package com.shiwushop.marketing.service.impl;

import com.shiwushop.marketing.PromotionAmounts;
import com.shiwushop.marketing.RefundUnits;
import com.shiwushop.marketing.ActivityContentHash;
import tools.jackson.databind.ObjectMapper;
import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.marketing.service.MarketingPricingService;
import com.shiwushop.marketing.vo.MarketingOrderSource;
import com.shiwushop.member.service.MemberPointRedemptionService;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.*;
import com.shiwushop.marketing.model.MarketingBudgetHold;
import com.shiwushop.marketing.service.impl.CouponBudgetCommitments;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MarketingOrderSettlementImpl implements MarketingOrderSettlement {
    private final MarketingOrderMapper orders;
    private final MarketingOrderSourceMapper sources;
    private final MarketingGroupMapper groups;
    private final MarketingStockMapper stockRows;
    private final MarketingStockLedger stock;
    private final MemberPointRedemptionService points;
    private final ActivityContentHash hash;
    private final MarketingBudgetHoldMapper holds;
    private final MarketingActivityMapper activities;
    private final MarketingCouponMapper coupons;
    private final CouponBudgetCommitments commitments;
    private final Clock clock;
    private final ObjectMapper json;
    /** 营销结算中金额比较和累计使用的零值，金额单位为元。 */
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static BusinessException conflict(String reason, String message) {
        return new BusinessException(409, message, Map.of("reason", reason), null);
    }

    @Override
    public MarketingOrderSource prepareSource(Long userId, String businessType, Long activitySkuId,
                                              int quantity, Long groupId, Long bargainId, String requestKey) {
        if (userId == null || activitySkuId == null || quantity <= 0 || requestKey == null
                || !requestKey.matches("[!-~]{1,64}"))
            throw new BusinessException(400, "活动下单参数不正确");
        if (businessType == null || !Set.of("FLASH", "GROUP", "BARGAIN", "POINTS").contains(businessType))
            throw new BusinessException(400, "活动下单类型不正确");
        if ((groupId != null && !"GROUP".equals(businessType))
                || (bargainId != null && !"BARGAIN".equals(businessType))
                || ("BARGAIN".equals(businessType) && bargainId == null)
                || (("GROUP".equals(businessType) || "BARGAIN".equals(businessType)) && quantity != 1))
            throw conflict("ACTIVITY_SOURCE_INVALID", "活动来源与件数不符");
        var scope = sources.scope(activitySkuId);
        if (scope == null || !businessType.equals(scope.businessType())
                || !("POINTS".equals(businessType) ? "REDEEM" : "SALE").equals(scope.scopeRole()))
            throw conflict("ACTIVITY_SOURCE_INVALID", "活动商品与业务类型不符");
        var now = LocalDateTime.now(clock);
        var digest = hash.hash(Arrays.asList("ACTIVITY_ORDER", businessType, activitySkuId,
                quantity, groupId, bargainId));
        var previous = sources.byRequest(userId, requestKey);
        if (previous != null && (!previous.activitySkuId().equals(activitySkuId)
                || previous.quantity() != quantity || !Arrays.equals(previous.requestHash(), digest)))
            throw conflict("IDEMPOTENCY_CONFLICT", "同一请求键对应不同活动来源");
        MarketingOrderSourceMapper.GroupRoot group = null;
        MarketingOrderSourceMapper.BargainRoot bargain = null;
        if ("GROUP".equals(businessType)) {
            Long rootId = groupId == null && previous != null ? previous.groupId() : groupId;
            if (rootId != null) {
                group = sources.lockGroup(rootId);
                if (group == null || !group.activitySkuId().equals(activitySkuId)
                        || (previous == null && (!"FORMING".equals(group.status())
                        || group.openedAt() == null || !group.expiresAt().isAfter(now))))
                    throw conflict("GROUP_NOT_READY", "拼团不可参与");
            }
        } else if ("BARGAIN".equals(businessType)) {
            bargain = sources.lockBargain(bargainId);
            if (bargain == null || !bargain.userId().equals(userId)
                    || !bargain.activitySkuId().equals(activitySkuId)
                    || (previous == null && (!"QUALIFIED".equals(bargain.status())
                    || bargain.purchaseExpiresAt() == null || !bargain.purchaseExpiresAt().isAfter(now))))
                throw conflict("BARGAIN_NOT_QUALIFIED", "砍价资格不可购买");
        }
        if (previous != null) {
            var locked = sources.lockParticipation(previous.id());
            if (locked == null || !locked.userId().equals(userId)
                    || !locked.activitySkuId().equals(activitySkuId)
                    || ("GROUP".equals(businessType) && !Objects.equals(locked.groupId(), group.id()))
                    || ("BARGAIN".equals(businessType) && !locked.id().equals(bargain.participationId())))
                throw conflict("IDEMPOTENCY_CONFLICT", "活动参与身份已变化");
            if (locked.orderId() != null && sources.replayOrder(locked.orderId(), userId, requestKey) == null)
                throw conflict("ACTIVITY_ALREADY_ORDERED", "活动参与已转为其他订单");
            return source(locked, scope, group, bargain);
        }
        if (!"RUNNING".equals(scope.status()) || now.isBefore(scope.startAt())
                || !now.isBefore(scope.endAt()) || !"ON_SALE".equals(scope.productStatus())
                || !"ACTIVE".equals(scope.skuStatus()))
            throw conflict("ACTIVITY_NOT_AVAILABLE", "活动商品当前不可购买");
        MarketingOrderSourceMapper.Participation participation;
        if (bargain != null) {
            participation = sources.lockParticipation(bargain.participationId());
            if (participation == null || !participation.userId().equals(userId)
                    || !participation.activitySkuId().equals(activitySkuId)
                    || !"QUALIFIED".equals(participation.status()) || participation.orderId() != null)
                throw conflict("BARGAIN_NOT_QUALIFIED", "砍价参与身份不可转单");
        } else {
            LocalDateTime expires = scope.endAt();
            if ("GROUP".equals(businessType)) {
                if (scope.teamSize() == null || scope.participationSeconds() == null)
                    throw conflict("ACTIVITY_SOURCE_INVALID", "拼团规则不完整");
                if (group == null) {
                    sources.insertGroup(activitySkuId, userId, scope.teamSize(), expires);
                    group = sources.lockGroup(sources.lastId());
                } else {
                    if (sources.groupMember(group.id(), userId) != null)
                        throw conflict("GROUP_NOT_READY", "同一账号已参加该团");
                    expires = group.expiresAt().isBefore(expires) ? group.expiresAt() : expires;
                }
            }
            sources.insertParticipation(activitySkuId, userId, requestKey, digest, quantity,
                    expires, group == null ? null : group.id());
            participation = sources.lockParticipation(sources.lastId());
        }
        return source(participation, scope, group, bargain);
    }

    @Override
    public MarketingOrderSource lockSource(Long orderId) {
        var association = sources.association(orderId);
        if (association == null) return null;
        MarketingOrderSourceMapper.GroupRoot group = association.groupId() == null ? null
                : sources.lockGroup(association.groupId());
        MarketingOrderSourceMapper.BargainRoot bargain = association.bargainId() == null ? null
                : sources.lockBargain(association.bargainId());
        var participation = sources.lockParticipation(association.participationId());
        if (participation == null) throw new IllegalStateException("订单活动来源不存在");
        return source(participation, sources.scope(participation.activitySkuId()), group, bargain);
    }

    private MarketingOrderSource source(MarketingOrderSourceMapper.Participation participation,
                                        MarketingOrderSourceMapper.Scope scope,
                                        MarketingOrderSourceMapper.GroupRoot group,
                                        MarketingOrderSourceMapper.BargainRoot bargain) {
        if (scope == null) throw new IllegalStateException("活动商品来源不存在");
        String type = scope.businessType();
        BigDecimal cash = ZERO;
        long units = 0;
        if ("POINTS".equals(type)) {
            if (scope.pointCostUnits() == null) throw new IllegalStateException("积分兑换价格不存在");
            units = Math.multiplyExact(scope.pointCostUnits(), participation.quantity());
        } else {
            BigDecimal price = "BARGAIN".equals(type) ? bargain.currentAmount() : scope.activityPrice();
            if (price == null) throw new IllegalStateException("活动现金价格不存在");
            cash = price.multiply(BigDecimal.valueOf(participation.quantity()));
        }
        LocalDateTime expires = participation.expiresAt();
        if (group != null && group.expiresAt().isBefore(expires)) expires = group.expiresAt();
        if (bargain != null && bargain.purchaseExpiresAt() != null
                && bargain.purchaseExpiresAt().isBefore(expires)) expires = bargain.purchaseExpiresAt();
        return new MarketingOrderSource(participation.id(), scope.activityId(), scope.activitySkuId(),
                scope.shopId(), scope.productId(), scope.skuId(), participation.quantity(), cash, units,
                type, expires, participation.groupId(), bargain == null ? null : bargain.id());
    }

    /** 与建单同事务保存优惠快照、占券及限购；承担方预算不足会回滚整单。 */
    @Override
    public void reserve(long order, long shop, long user, LocalDateTime deadline, Long coupon,
                        Map<Long, Long> items, List<MarketingPricingService.Allocation> discounts,
                        Long participationId) {
        if (participationId != null) {
            var participation = sources.participation(participationId);
            if (participation == null || !participation.userId().equals(user)
                    || participation.orderId() != null
                    || !("RESERVED".equals(participation.status()) || "QUALIFIED".equals(participation.status())))
                throw conflict("ACTIVITY_SOURCE_INVALID", "活动参与身份不可转单");
            var scope = sources.scope(participation.activitySkuId());
            if (scope == null)
                throw conflict("ACTIVITY_SOURCE_INVALID", "活动商品来源不存在");
            var group = participation.groupId() == null ? null : sources.group(participation.groupId());
            var bargain = "BARGAIN".equals(scope.businessType())
                    ? sources.bargainForParticipation(participationId) : null;
            var source = source(participation, scope, group, bargain);
            var facts = sources.orderFacts(order);
            var now = LocalDateTime.now(clock);
            if (facts == null || facts.itemCount() != 1 || !facts.userId().equals(user)
                    || !facts.shopId().equals(shop) || !source.shopId().equals(shop)
                    || !facts.businessType().equals(source.businessType())
                    || !facts.skuId().equals(source.skuId()) || facts.quantity() != source.quantity()
                    || !Objects.equals(items.get(source.skuId()), facts.itemId())
                    || (facts.sourceActivityId() != null
                    && !facts.sourceActivityId().equals(source.activityId()))
                    || ("POINTS".equals(source.businessType())
                    ? facts.totalAmount().signum() != 0 || facts.payableAmount().signum() != 0 || coupon != null
                    : facts.unitPrice().multiply(BigDecimal.valueOf(source.quantity()))
                    .compareTo(source.cashTotal()) != 0 || facts.totalAmount().compareTo(source.cashTotal()) > 0)
                    || !source.expiresAt().isAfter(now)
                    || (deadline != null && deadline.isAfter(source.expiresAt())))
                throw conflict("ACTIVITY_SOURCE_INVALID", "订单与活动来源或价格不符");
            long pointEntry = 0;
            if ("POINTS".equals(source.businessType()))
                pointEntry = points.spend(user, order, source.pointUnits());
            var current = sources.lockScope(source.activitySkuId());
            if (current == null || !"RUNNING".equals(current.status())
                    || !"ON_SALE".equals(current.productStatus()) || !"ACTIVE".equals(current.skuStatus())
                    || !current.activityId().equals(source.activityId())
                    || now.isBefore(current.startAt()) || !now.isBefore(current.endAt())
                    || (deadline != null && deadline.isAfter(current.endAt()))
                    || ("POINTS".equals(source.businessType())
                    ? !Objects.equals(current.pointCostUnits(), scope.pointCostUnits())
                    : !"BARGAIN".equals(source.businessType())
                    && (current.activityPrice() == null
                    || current.activityPrice().compareTo(scope.activityPrice()) != 0)))
                throw conflict("ACTIVITY_NOT_AVAILABLE", "活动商品已不可购买");
            if (sources.bind(participationId, user, order, now) != 1)
                throw conflict("ACTIVITY_ALREADY_ORDERED", "活动参与已转单或到期");
            if (facts.sourceActivityId() == null
                    && sources.bindItem(facts.itemId(), order, source.activityId()) != 1)
                throw new IllegalStateException("活动订单项来源绑定失败");
            if ("BARGAIN".equals(source.businessType())) {
                var held = stockRows.entry("BARGAIN:START:" + participationId);
                if (held == null || !"RESERVE".equals(held.reason())
                        || !held.participationId().equals(participationId))
                    throw conflict("ACTIVITY_SOURCE_INVALID", "砍价原库存占用不存在");
                if (sources.orderBargain(source.bargainId(), now) != 1)
                    throw conflict("BARGAIN_NOT_QUALIFIED", "砍价资格已变化");
            } else {
                stock.reserve(source.activitySkuId(), participationId, source.quantity(),
                        "ACTIVITY:ORDER:" + participationId);
            }
            if (source.groupId() != null && sources.reserveGroupSeat(source.groupId(), now) != 1)
                throw conflict("GROUP_FULL", "团名额已满或已截止");
            if (orders.scopeDelta(source.activitySkuId(), source.quantity(), 0) != 1)
                throw conflict("PURCHASE_LIMIT_REACHED", "活动销售上限不足");
            if (current.perUserLimit() != null) {
                var activity = activities.lock(source.activityId());
                orders.initializeBuy(source.activitySkuId(), activity.getActivityCode(), user);
                if (orders.quotaDelta(activity.getActivityCode(), user, source.quantity(), 0,
                        current.perUserLimit()) != 1)
                    throw conflict("PURCHASE_LIMIT_REACHED", "活动账号限购不足");
            }
            if (pointEntry != 0)
                sources.insertRedemption(order, participationId, source.activitySkuId(), user,
                        source.pointUnits(), pointEntry, participation.requestKey());
        }
        Long version = null;
        if (coupon != null) {
            if (orders.reserveCoupon(coupon, user, order, deadline, LocalDateTime.now(clock)) != 1)
                throw conflict("COUPON_NOT_USABLE", "券已过期或被占用");
            version = orders.couponVersion(coupon);
        }
        for (var a : discounts)
            orders.snapshot(items.get(a.skuId()), a, a.userCouponId() == null ? null : version);
        counters(order, user, true, false);
        for (var a : discounts) {
            long item = items.get(a.skuId());
            for (var budget : activities.lockBudgets(a.activityId())) {
                if (budget.shopId() != null && budget.shopId() != shop)
                    continue;
                BigDecimal amount =
                        budget.shopId() == null ? a.platformAmount() : a.amount().subtract(a.platformAmount());
                if (amount.signum() == 0)
                    continue;
                String key = "ORDER:" + order + ":" + item + ":" + a.activityId();
                var hold = new MarketingBudgetHold();
                hold.setBudgetId(budget.id());
                hold.setSourceKind("ORDER");
                hold.setSourceId(order);
                hold.setOperationKey(key);
                hold.setAuthorizedAmount(amount);
                hold.setHeldAmount(amount);
                hold.setExpiresAt(deadline);
                boolean transfer = a.userCouponId() != null;
                // 券已在发放时预占预算，此处只把承诺转给订单，不能再次增加总占用。
                if (transfer) {
                    var parent = holds.couponHolds(coupon).stream().filter(h -> h.getBudgetId().equals(budget.id())
                            && h.getHeldAmount().compareTo(amount) >= 0).findFirst().orElseThrow();
                    if (orders.transfer(parent.getId(), amount) != 1)
                        throw new IllegalStateException("券预算承诺余额不一致");
                    hold.setParentHoldId(parent.getId());
                } else if (orders.changeBudget(budget.id(), amount, ZERO) != 1) {
                    throw conflict("BUDGET_NOT_ENOUGH", "活动预算不足");
                }
                holds.insert(hold);
                orders.entry(budget.id(), hold.getId(), key, transfer ? "TRANSFER" : "RESERVE",
                        transfer ? ZERO : amount, ZERO, order, null, LocalDateTime.now(clock));
            }
        }
    }

    private void counters(long order, long user, boolean reserve, boolean paid) {
        for (var scope : orders.scopes(order)) {
            int held = reserve ? scope.quantity() : -scope.quantity();
            int consumed = paid ? scope.quantity() : 0;
            if (orders.scopeDelta(scope.id(), held, consumed) != 1)
                throw conflict("PURCHASE_LIMIT_REACHED", "活动销售上限不足");
            if (scope.perUserLimit() != null) {
                if (reserve)
                    orders.initializeBuy(scope.id(), scope.code(), user);
                if (orders.quotaDelta(scope.code(), user, held, consumed, scope.perUserLimit()) != 1)
                    throw conflict("PURCHASE_LIMIT_REACHED", "活动账号限购不足");
            }
        }
    }

    /** 调用方先锁订单并完成状态去重；paid 区分支付核销与未付取消，不用于已付退款。 */
    @Override
    public void finish(long order, long user, boolean paid) {
        var association = sources.association(order);
        if (association != null) {
            var participation = sources.participation(association.participationId());
            if (participation == null || !participation.userId().equals(user)
                    || !Objects.equals(participation.orderId(), order)
                    || !"RESERVED".equals(participation.status()))
                throw conflict("ACTIVITY_SOURCE_INVALID", "活动订单参与状态不符");
            var scope = sources.scope(participation.activitySkuId());
            if (paid)
                stock.confirm(participation.activitySkuId(), participation.id(), participation.quantity(),
                        "ACTIVITY:COMMIT:" + participation.id());
            else
                stock.release(participation.activitySkuId(), participation.id(), participation.quantity(),
                        "ACTIVITY:CANCEL:" + participation.id());
            if (orders.scopeDelta(participation.activitySkuId(), -participation.quantity(),
                    paid ? participation.quantity() : 0) != 1)
                throw new IllegalStateException("活动销售量结算失败");
            if (scope.perUserLimit() != null) {
                var activity = activities.lock(scope.activityId());
                if (orders.quotaDelta(activity.getActivityCode(), user, -participation.quantity(),
                        paid ? participation.quantity() : 0, scope.perUserLimit()) != 1)
                    throw new IllegalStateException("活动账号额度结算失败");
            }
            boolean lateGroup = false;
            var now = LocalDateTime.now(clock);
            var groupDeadline = now.plusSeconds(scope.participationSeconds() == null
                    ? 0 : scope.participationSeconds());
            if (groupDeadline.isAfter(scope.endAt())) groupDeadline = scope.endAt();
            if (participation.groupId() != null
                    && sources.finishGroupSeat(participation.groupId(), paid ? 1 : 0,
                    now, groupDeadline) != 1) {
                var group = sources.group(participation.groupId());
                if (!paid || group == null || !("FAILED".equals(group.status())
                        || ("FORMING".equals(group.status()) && groups.fail(group.id(), now) == 1)))
                    throw conflict("GROUP_NOT_READY", "拼团状态已变化");
                lateGroup = true;
            }
            if (sources.finishParticipation(participation.id(), order,
                    lateGroup ? "REFUND_PENDING" : paid ? "PAID" : "RELEASED", LocalDateTime.now(clock)) != 1)
                throw new IllegalStateException("活动参与结算失败");
            if (association.bargainId() != null && !paid)
                sources.closeBargain(participation.id());
            if ("POINTS".equals(scope.businessType())) {
                if (paid) {
                    if (sources.redemptionState(order, "CREATED", "FULFILLING") != 1)
                        throw new IllegalStateException("积分兑换状态确认失败");
                } else {
                    long units = Math.multiplyExact(scope.pointCostUnits(), participation.quantity());
                    points.returnSpent(user, order, null, units, "CANCEL", false);
                    if (sources.returnPoints(order, units) != 1)
                        throw new IllegalStateException("积分兑换返还余额不一致");
                }
            }
        }
        counters(order, user, false, paid);
        var use = orders.couponUse(order);
        var coupon = use == null ? null : coupons.lockCoupon(use.id());
        boolean expired = coupon != null && !coupon.getExpiresAt().isAfter(LocalDateTime.now(clock));
        if (use != null && orders.finishCoupon(use, order, paid ? "USED" : expired ? "EXPIRED" : "AVAILABLE",
                paid ? order : null) != 1)
            throw new IllegalStateException("订单与券占用版本不一致");
        orders.lockOrderBudgets(order);
        for (var hold : orders.holds(order)) {
            var amount = hold.getHeldAmount();
            // 未付取消且券仍有效时归还券的承诺，保留再次用券所需的资金。
            boolean restore = !paid && hold.getParentHoldId() != null && !expired;
            var heldDelta = restore ? ZERO : amount.negate();
            var spentDelta = paid ? amount : ZERO;
            if (restore)
                orders.restoreParent(hold.getParentHoldId(), amount);
            if (!restore && orders.changeBudget(hold.getBudgetId(), heldDelta, spentDelta) != 1)
                throw new IllegalStateException("订单预算结算失败");
            orders.finishHold(hold.getId(), spentDelta, paid ? ZERO : amount, paid ? "SPENT" : "RELEASED");
            orders.entry(hold.getBudgetId(), hold.getId(), (paid ? "COMMIT:" : "CANCEL:") + hold.getId(),
                    paid ? "COMMIT" : restore ? "TRANSFER" : "RELEASE", heldDelta, spentDelta, order, null,
                    LocalDateTime.now(clock));
        }
        if (expired)
            commitments.releaseExpired(coupon.getActivityId(), coupon.getId());
    }

    /** 按成交快照冲回承担并处理返券；已支付订单的本场限购不恢复。 */
    @Override
    public void refund(long order, long sale) {
        var association = sources.association(order);
        if (association != null) {
            var participation = sources.participation(association.participationId());
            int quantity = sources.refundQuantity(sale, order);
            if (participation == null || quantity <= 0 || quantity > participation.quantity()
                    || !("PAID".equals(participation.status())
                    || "REFUND_PENDING".equals(participation.status())))
                throw conflict("ACTIVITY_SOURCE_INVALID", "活动退款来源不符");
            var scope = sources.scope(participation.activitySkuId());
            if ("POINTS".equals(scope.businessType())) {
                long units = Math.multiplyExact(scope.pointCostUnits(), quantity);
                points.returnSpent(participation.userId(), order, sale, units, "AFTERSALE:" + sale, false);
                if (sources.returnPoints(order, units) != 1)
                    throw new IllegalStateException("积分兑换退款余额不一致");
            }
            stock.refund(participation.activitySkuId(), participation.id(), quantity,
                    "ACTIVITY:REFUND:" + sale + ":" + participation.id());
            if (sources.refundedQuantity(order) >= participation.quantity())
                sources.refundedParticipation(participation.id());
        }
        var now = LocalDateTime.now(clock);
        var use = orders.couponUse(order);
        var coupon = use == null ? null : coupons.lockCoupon(use.id());
        boolean couponValid = coupon != null && coupon.getExpiresAt().isAfter(now);
        orders.lockOrderBudgets(order);
        var orderHolds = orders.holds(order);
        orders.refundedBase(sale, now);
        for (var a : orders.refundShares(sale)) {
            var ranges = json.readValue(a.ranges(), RefundUnits.Range[].class);
            BigDecimal platform = ZERO, merchant = ZERO;
            // 按原商品单位序号计算余分，多次部分退款不能重新平均分配。
            for (var range : ranges) {
                platform = platform.add(PromotionAmounts.units(a.platform(), a.quantity(), range.from(), range.to()));
                merchant = merchant.add(PromotionAmounts.units(a.merchant(), a.quantity(), range.from(), range.to()));
            }
            orders.refundedShare(a, platform, merchant, now);
            String key = "ORDER:" + order + ":" + a.orderItem() + ":" + a.activity();
            var budgets = activities.lockBudgets(a.activity());
            for (var h : orderHolds) {
                if (!h.getOperationKey().equals(key))
                    continue;
                var budget = budgets.stream().filter(b -> b.id().equals(h.getBudgetId())).findFirst().orElseThrow();
                var amount = budget.shopId() == null ? platform : merchant;
                if (amount.signum() == 0)
                    continue;
                if (orders.refundHold(h.getId(), amount) != 1)
                    throw new IllegalStateException("退款超过原承担快照");
                boolean restore = h.getParentHoldId() != null && couponValid;
                var held = restore ? amount : ZERO;
                if (restore)
                    orders.restoreParent(h.getParentHoldId(), amount);
                if (orders.changeBudget(h.getBudgetId(), held, amount.negate()) != 1)
                    throw new IllegalStateException("退款预算不平");
                orders.entry(h.getBudgetId(), h.getId(), "REFUND:" + a.saleItem() + ":" + h.getId(), "REFUND",
                        held, amount.negate(), order, sale, now);
            }
        }
        // 必须所有 SALE 数量退完，包括零实付明细，不能只看现金退款总额。
        boolean full = orders.fullyReturned(order);
        if (coupon == null)
            return;
        if (!couponValid)
            commitments.releaseExpired(coupon.getActivityId(), coupon.getId());
        if (!full)
            return;
        if (orders.returnCoupon(use, order, couponValid ? "AVAILABLE" : "EXPIRED") != 1)
            throw new IllegalStateException("返券的订单或版本不一致");
        if (!couponValid)
            return;
        // 返券创建新的承诺代次，保留原订单转移链，避免复用已结算的占用记录。
        for (var parent : holds.couponHolds(coupon.getId())) {
            if (parent.getHeldAmount().signum() == 0)
                continue;
            holds.release(parent.getId());
            var next = new MarketingBudgetHold();
            next.setBudgetId(parent.getBudgetId());
            next.setParentHoldId(parent.getId());
            next.setSourceKind("COUPON");
            next.setSourceId(coupon.getId());
            next.setExpiresAt(coupon.getExpiresAt());
            next.setOperationKey("RETURN:" + order + ":" + parent.getId());
            next.setHeldAmount(parent.getHeldAmount());
            next.setAuthorizedAmount(parent.getHeldAmount());
            holds.insert(next);
            orders.entry(next.getBudgetId(), next.getId(), next.getOperationKey(), "TRANSFER", ZERO, ZERO, order, sale,
                    now);
        }
    }
}
