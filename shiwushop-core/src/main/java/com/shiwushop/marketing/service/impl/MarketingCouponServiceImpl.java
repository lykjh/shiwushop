package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.ActivityDraftRules;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.dto.*;
import com.shiwushop.marketing.mapper.*;
import com.shiwushop.marketing.model.*;
import com.shiwushop.marketing.vo.*;
import com.shiwushop.marketing.service.MarketingCouponService;
import com.shiwushop.member.service.MemberAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MarketingCouponServiceImpl implements MarketingCouponService {
    private final MarketingCouponMapper mapper;
    private final MarketingActivityMapper activities;
    private final CouponIssuance issuance;
    private final CouponBudgetCommitments commitments;
    private final MemberAccountService members;
    private final AccountSecurity security;
    private final MarketingSettings settings;
    private final ActivityContentHash hash;
    private final ObjectMapper json;
    private final Clock businessClock;

    private LocalDateTime now() {
        return LocalDateTime.now(businessClock).truncatedTo(ChronoUnit.MILLIS);
    }

    private void enabled() {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }

    private static void conflict(String reason, String message) {
        throw new BusinessException(409, message, Map.of("reason", reason), null);
    }

    private MarketingActivity activity(Long id) {
        var activity = activities.lock(id);
        if (activity == null || !activity.getActivityType().equals("COUPON"))
            throw new BusinessException(404, "券活动不存在");
        return activity;
    }

    private ActivityCouponRule usable(MarketingActivity activity, String mode, String level) {
        var rule = activities.coupon(activity.getId());
        var now = now();
        if (!Set.of("APPROVED", "RUNNING").contains(activity.getStatus()) || now.isBefore(activity.getStartAt())
                || !now.isBefore(activity.getEndAt()) || !now.isBefore(rule.claimEndAt()) ||
                !rule.grantMode().equals(mode))
            conflict("ACTIVITY_NOT_AVAILABLE", "券活动当前不可发放");
        if (!Arrays.asList(json.readValue(activity.getEligibleLevelCodes(), String[].class)).contains(level))
            conflict("LEVEL_NOT_ELIGIBLE", "当前等级不符合领券条件");
        return rule;
    }

    private CouponGrant newGrant(Long user, MarketingActivity activity, ActivityCouponRule rule, String kind,
                                 String key, int count) {
        var grant = new CouponGrant();
        grant.setUserId(user);
        grant.setActivityId(activity.getId());
        grant.setSourceKind(kind);
        grant.setSourceKey(key);
        grant.setEntitledCount(count);
        grant.setFaceAmount(rule.faceAmount());
        grant.setThresholdAmount(rule.thresholdAmount());
        var expiry = now().plusDays(rule.validityDays());
        if (rule.fixedExpireAt() != null && rule.fixedExpireAt().isBefore(expiry))
            expiry = rule.fixedExpireAt();
        if (!expiry.isAfter(now()))
            conflict("ACTIVITY_NOT_AVAILABLE", "券的固定期限已结束");
        grant.setExpiresAt(expiry);
        grant.setStatus("PENDING");
        grant.setNextAttemptAt(now());
        return grant;
    }

    /** 同用户、活动及请求键复用原发放义务，重试不重复消耗领取额度。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponGrantVO claim(Long user, Long activityId, String key) {
        security.checkActive(user);
        enabled();
        members.levels();
        var member = members.initializeAndLock(user);
        var activity = activity(activityId);
        String source = activityId + ":" + key;
        var previous = mapper.source(user, "CLAIM", source);
        if (previous != null)
            return issuance.grantView(previous, true);
        var rule = usable(activity, "CLAIM", member.getLevelCode());
        var grant = newGrant(user, activity, rule, "CLAIM", source, 1);
        mapper.insertGrant(grant);
        issuance.issue(grant, activity, rule);
        return issuance.grantView(grant, true);
    }

    /** 兑换码扣次、创建发放义务及预算预占同事务，后续失败不能吞掉兑换次数。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponGrantVO redeem(Long user, String key, CouponRedemption request) {
        security.checkActive(user);
        enabled();
        members.levels();
        var member = members.initializeAndLock(user);
        var digest = hash.hash(request.code());
        var previous = mapper.source(user, "CODE", key);
        if (previous != null) {
            if (!Arrays.equals(mapper.lockCode(previous.getCodeId()).getCodeHash(), digest))
                conflict("IDEMPOTENCY_CONFLICT", "兑换请求键对应不同兑换码");
            return issuance.grantView(previous, true);
        }
        var code = mapper.code(digest);
        if (code == null)
            conflict("CODE_NOT_USABLE", "兑换码不可用");
        var activity = activity(code.getActivityId());
        var rule = usable(activity, "CODE", member.getLevelCode());
        if (mapper.consumeCode(code.getId(), now()) != 1)
            conflict("CODE_NOT_USABLE", "兑换码不可用");
        var grant = newGrant(user, activity, rule, "CODE", key, 1);
        grant.setCodeId(code.getId());
        mapper.insertGrant(grant);
        issuance.issue(grant, activity, rule);
        return issuance.grantView(grant, true);
    }

    /** 补偿重试核对完整请求摘要，禁止用同一幂等键更换收券人或凭据。 */
    @Transactional
    public CouponGrantVO compensate(Long actor, Long activityId, String key, CompensationGrant request) {
        security.requireAdmin(actor);
        enabled();
        security.checkActive(request.userId());
        members.levels();
        var member = members.initializeAndLock(request.userId());
        var activity = activity(activityId);
        String source = "COMPENSATION:" + actor + ":" + activityId + ":" + key;
        var previous = mapper.compensation(actor, activityId, source);
        if (previous != null) {
            if (!Arrays.equals(previous.getRequestHash(), hash.hash(request)))
                conflict("IDEMPOTENCY_CONFLICT", "补偿请求键对应不同内容或收券用户");
            return issuance.grantView(previous, true);
        }
        var rule = usable(activity, "COMPENSATION", member.getLevelCode());
        var grant = newGrant(request.userId(), activity, rule, "COMPENSATION", source, request.count());
        grant.setActorUserId(actor);
        grant.setReason(request.reason());
        grant.setEvidenceReference(request.evidenceRef());
        grant.setRequestHash(hash.hash(request));
        mapper.insertGrant(grant);
        issuance.issue(grant, activity, rule);
        mapper.audit(actor, "COUPON_COMPENSATED", "COUPON_GRANT", grant.getId(),
                json.writeValueAsString(Map.of("grantId", grant.getId(),
                        "ledger", "marketing_coupon_grants", "reasonField", "reason", "evidenceField",
                        "evidence_reference")), now());
        return issuance.grantView(grant, true);
    }

    @Transactional
    public List<CouponCodeVO> createCodes(Long actor, Long activityId, String key, CouponCodeBatch request) {
        security.requireAdmin(actor);
        enabled();
        var activity = activity(activityId);
        var old = mapper.batch(actor, activityId, key);
        if (!old.isEmpty()) {
            if (!Arrays.equals(old.getFirst().getCreationRequestHash(), hash.hash(request)))
                conflict("IDEMPOTENCY_CONFLICT", "兑换码批次内容或顺序不同");
            return old.stream().map(CouponCodeVO::from).toList();
        }
        var rule = activities.coupon(activityId);
        if (!rule.grantMode().equals("CODE") || !Set.of("APPROVED", "RUNNING").contains(activity.getStatus())
                || !rule.claimEndAt().isAfter(now()) || !activity.getEndAt().isAfter(now()))
            conflict("ACTIVITY_NOT_AVAILABLE", "活动当前不能创建兑换码");
        var codes = new ArrayList<CouponCode>();
        var unique = new HashSet<String>();
        for (int index = 0; index < request.codes().size(); index++) {
            var input = request.codes().get(index);
            var expiry = ActivityDraftRules.time(input.expiresAt());
            if (!unique.add(input.code()) || (input.codeKind().equals("SINGLE") && input.maxRedemptions() != 1)
                    || !expiry.isAfter(now()) || expiry.isAfter(rule.claimEndAt()))
                throw new BusinessException(400, "兑换码批次存在重复、数量或期限错误",
                        Map.of("reason", "INVALID_ARGUMENT"), null);
            var code = new CouponCode();
            code.setActivityId(activityId);
            // 仅持久化兑换码摘要，管理查询不能还原可直接兑换的明文。
            code.setCodeHash(hash.hash(input.code()));
            code.setCodeKind(input.codeKind());
            code.setMaxRedemptions(input.maxRedemptions());
            code.setExpiresAt(expiry);
            code.setStatus("ACTIVE");
            code.setCreatorUserId(actor);
            code.setCreationRequestKey(key);
            code.setCreationRequestHash(hash.hash(request));
            code.setCreationOrdinal(index + 1);
            codes.add(code);
        }
        // 统一唯一键写入顺序，批次返回仍保持提交序号。
        try {
            for (var code : codes.stream().sorted(Comparator.comparing(c -> HexFormat.of().formatHex(c.getCodeHash())))
                    .toList())
                mapper.insertCode(code);
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(409, "兑换码已存在", Map.of("reason", "CODE_NOT_USABLE"), duplicate);
        }
        mapper.audit(actor, "COUPON_CODES_CREATED", "MARKETING_ACTIVITY", activityId,
                json.writeValueAsString(Map.of("firstCodeId", codes.getFirst().getId(), "count", codes.size())), now());
        return codes.stream().map(CouponCodeVO::from).toList();
    }

    @Transactional
    public CouponCodeVO disableCode(Long actor, Long id, ActivityReason request) {
        security.requireAdmin(actor);
        enabled();
        var code = mapper.lockCode(id);
        if (code == null)
            throw new BusinessException(404, "兑换码记录不存在");
        if (code.getStatus().equals("DISABLED"))
            return CouponCodeVO.from(code);
        mapper.disable(id);
        mapper.audit(actor, "COUPON_CODE_DISABLED", "COUPON_CODE", id, request.reason(), now());
        code.setStatus("DISABLED");
        return CouponCodeVO.from(code);
    }

    @Transactional(readOnly = true)
    public CouponCodeVO.Page codes(Long actor, Long activityId, Long afterId, int size, String status) {
        security.requireAdmin(actor);
        enabled();
        var activity = activities.find(activityId);
        if (activity == null || !activity.getActivityType().equals("COUPON"))
            throw new BusinessException(404, "券活动不存在");
        var rows = mapper.codes(activityId, afterId, status, size + 1);
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(CouponCodeVO::from).toList();
        return new CouponCodeVO.Page(items, more, more ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true)
    public CouponVO.Page coupons(Long user, Long afterId, int size, String status, Long shop) {
        security.checkActive(user);
        enabled();
        var rows = mapper.coupons(user, afterId, status, shop, now(), size + 1);
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(issuance::couponView).toList();
        return new CouponVO.Page(items, more, more ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true)
    public CouponGrantVO.Page grants(Long user, Long afterId, int size, String kind) {
        security.checkActive(user);
        enabled();
        var rows = mapper.grants(user, afterId, kind, size + 1);
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(g -> issuance.grantView(g, false)).toList();
        return new CouponGrantVO.Page(items, more, more ? items.getLast().id() : null);
    }

    public List<Long> expiredCoupons() {
        return mapper.expired(now());
    }

    /** RESERVED 券由订单收尾；已用券只释放剩余承诺，保留 USED 状态供退款追溯。 */
    @Transactional
    public void expire(Long id) {
        var coupon = mapper.lockCoupon(id);
        if (!Set.of("AVAILABLE", "USED").contains(coupon.getStatus()) || coupon.getExpiresAt().isAfter(now()))
            return;
        commitments.releaseExpired(coupon.getActivityId(), id);
        if (coupon.getStatus().equals("AVAILABLE"))
            mapper.expire(id);
    }
}
