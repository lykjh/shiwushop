package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.*;
import com.shiwushop.marketing.model.CouponGrant;
import com.shiwushop.marketing.vo.MonthlyBenefitVO;
import com.shiwushop.member.MemberSettings;
import com.shiwushop.member.service.MemberAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MonthlyCouponService {
    private final MarketingSettings marketing;
    private final MemberSettings memberSettings;
    private final AccountSecurity security;
    private final MemberAccountService members;
    private final MarketingCouponMapper coupons;
    private final MarketingActivityMapper activities;
    private final MonthlyCouponMapper months;
    private final CouponIssuance issuance;
    private final Clock clock;

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String key(YearMonth month) {
        return "MEMBER:" + month;
    }

    private static OffsetDateTime time(LocalDateTime value) {
        return value.atOffset(ZoneOffset.ofHours(8));
    }

    public boolean enabled() {
        return marketing.enabled() && memberSettings.enabled();
    }

    private void requireEnabled() {
        if (!enabled())
            throw new BusinessException(404, "月券功能未启用");
    }

    public YearMonth firstMonth() {
        var first = months.firstEligibility();
        return first == null ? null : YearMonth.from(first);
    }

    public List<Long> missing(YearMonth month, int limit) {
        return months.missing(month.atDay(1).atStartOfDay(), key(month), limit);
    }

    public List<Long> due() {
        return months.due(now());
    }

    /** 按月初历史等级建立唯一发放义务，补扫不会使用今天的等级重算旧月份。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void establish(long user, YearMonth month) {
        members.initializeAndLock(user);
        if (coupons.source(user, "MEMBERSHIP", key(month)) != null)
            return;
        var cutoff = month.atDay(1).atStartOfDay();
        var level = members.levelAt(user, cutoff);
        if (level == null)
            return;
        var benefit = members.levels().stream().filter(l -> l.code().equals(level)).findFirst().orElseThrow();
        if (benefit.monthlyCouponCount() == 0)
            return;
        var grant = new CouponGrant();
        grant.setUserId(user);
        grant.setBenefitMonth(month.atDay(1));
        grant.setEligibleLevelCode(level);
        grant.setSourceKind("MEMBERSHIP");
        grant.setSourceKey(key(month));
        grant.setEntitledCount(benefit.monthlyCouponCount());
        grant.setFaceAmount(benefit.monthlyCouponFaceAmount());
        grant.setThresholdAmount(benefit.monthlyCouponThresholdAmount());
        // 有效期锚定权益月份，故障补发或管理员重试不能延长原期限。
        grant.setExpiresAt(cutoff.plusDays(30));
        grant.setNextAttemptAt(now());
        grant.setStatus(grant.getExpiresAt().isAfter(now()) ? "PENDING" : "EXPIRED");
        coupons.insertGrant(grant);
    }

    /** 短事务认领后再发券，进程失败时允许租约接管，不长时间占用任务锁。 */
    @Transactional
    public String acquire(long id) {
        var g = coupons.lockGrant(id);
        if (!Set.of("PENDING", "ATTENTION").contains(g.getStatus()))
            return null;
        var now = now();
        Long activity = g.getActivityId();
        if (activity == null && g.getExpiresAt().isAfter(now)) {
            var candidates = months.templates(g.getEligibleLevelCode(), g.getBenefitMonth().atStartOfDay());
            // 模板缺失或歧义留给后续 ATTENTION，不能随意选择一个承担方案。
            if (candidates.size() == 1)
                activity = candidates.getFirst();
        }
        String token = UUID.randomUUID().toString();
        return months.claim(id, activity, token, now, now.plusMinutes(1)) == 1 ? token : null;
    }

    /** 发券前再次验证租约归属与原到期日，过期工作者不能补发权益。 */
    @Transactional
    public void deliver(long id, String token) {
        var g = coupons.lockGrant(id);
        if (months.owns(id, token) == 0)
            return;
        if (!g.getExpiresAt().isAfter(now())) {
            months.result(id, token, "EXPIRED", null, now());
            return;
        }
        if (g.getActivityId() == null) {
            months.result(id, token, "ATTENTION", "MONTHLY_TEMPLATE_UNRESOLVED", now().plusMinutes(5));
            return;
        }
        var activity = activities.lock(g.getActivityId());
        var rule = activities.coupon(activity.getId());
        issuance.issue(g, activity, rule);
    }

    @Transactional
    public void attention(long id, String token, String error) {
        months.result(id, token, "ATTENTION", error, now().plusMinutes(5));
    }

    @Transactional(readOnly = true)
    public MonthlyBenefitVO benefit(long user, YearMonth month) {
        security.checkActive(user);
        requireEnabled();
        if (month.isAfter(YearMonth.from(now())))
            throw new BusinessException(400, "不能查询未来月份");
        var g = months.month(user, key(month));
        if (g != null)
            return new MonthlyBenefitVO(month.toString(), g.getEligibleLevelCode(), g.getEntitledCount(),
                    g.getFaceAmount(), g.getThresholdAmount(), time(g.getExpiresAt()), g.getStatus(),
                    issuance.grantView(g, false).coupons());
        var at = month.atDay(1).atStartOfDay();
        var code = members.levelAt(user, at);
        var level = members.levels().stream().filter(l -> l.code().equals(code)).findFirst().orElse(null);
        if (level == null || level.monthlyCouponCount() == 0)
            return new MonthlyBenefitVO(month.toString(), code, 0, BigDecimal.ZERO, BigDecimal.ZERO, null,
                    "NOT_ELIGIBLE", List.of());
        var expiry = at.plusDays(30);
        return new MonthlyBenefitVO(month.toString(), code, level.monthlyCouponCount(), level.monthlyCouponFaceAmount(),
                level.monthlyCouponThresholdAmount(), time(expiry), expiry.isAfter(now()) ? "PENDING" : "EXPIRED",
                List.of());
    }

    @Transactional(readOnly = true)
    public MonthlyBenefitVO.Page admin(long actor, Long user, String state, String source, Long after, int size) {
        security.requireAdmin(actor);
        requireEnabled();
        var rows = months.admin(user, state, source, after, size + 1);
        var items = rows.stream().limit(size).map(g -> new MonthlyBenefitVO.AdminGrant(issuance.grantView(g, false),
                g.getUserId(), g.getActivityId(),
                g.getBenefitMonth() == null ? null : YearMonth.from(g.getBenefitMonth()).toString(),
                g.getEligibleLevelCode(), g.getErrorCode(), time(g.getNextAttemptAt()))).toList();
        return new MonthlyBenefitVO.Page(items, rows.size() > size,
                rows.size() > size ? items.getLast().grant().id() : null);
    }

    /** 人工补发只重新排队并审计，实际发券仍走相同租约及预算事务。 */
    @Transactional
    public MonthlyBenefitVO.Retry retry(long actor, long id, String reason) {
        security.requireAdmin(actor);
        requireEnabled();
        var g = coupons.lockGrant(id);
        if (g == null)
            throw new BusinessException(404, "发放记录不存在");
        if (Set.of("ATTENTION", "PENDING").contains(g.getStatus()) && months.retry(id, now()) == 1) {
            coupons.audit(actor, "COUPON_GRANT_RETRY", "COUPON_GRANT", id, reason, now());
            g = coupons.lockGrant(id);
        }
        return new MonthlyBenefitVO.Retry(id, g.getStatus(), time(g.getNextAttemptAt()));
    }
}
