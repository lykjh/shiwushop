package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainHelpMapper;
import com.shiwushop.marketing.service.MarketingBargainHelpService;
import com.shiwushop.marketing.vo.BargainHelpVO;
import com.shiwushop.member.service.MemberAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** 砍价助力按活动公示的固定分值推进，账号日额度与事实同事务提交。 */
@Service
@RequiredArgsConstructor
public class MarketingBargainHelpServiceImpl implements MarketingBargainHelpService {
    private final MarketingBargainHelpMapper mapper;
    private final AccountSecurity security;
    private final MemberAccountService members;
    private final MarketingSettings settings;
    private final Clock businessClock;

    @Override
    @Transactional
    public BargainHelpVO help(Long helperUserId, long bargainId, String requestKey) {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
        security.checkActive(helperUserId);
        var root = mapper.lock(bargainId);
        if (root == null)
            throw new BusinessException(404, "砍价单不存在");
        String participationStatus = mapper.lockParticipation(root.participationId());
        // 砍价根与参与义务先于会员账户；同账号跨单同键竞争由账户锁串行。
        members.initializeAndLock(helperUserId);
        var byKey = mapper.helpByKey(helperUserId, requestKey);
        if (byKey != null) {
            if (!byKey.bargainId().equals(bargainId))
                throw conflict("IDEMPOTENCY_CONFLICT", "助力请求键对应不同砍价单");
            return original(byKey);
        }
        if (helperUserId.equals(root.userId()))
            throw conflict("SELF_HELP", "不能为自己的砍价助力");
        var previous = mapper.help(bargainId, helperUserId);
        if (previous != null) {
            if (!previous.requestKey().equals(requestKey))
                throw conflict("HELP_LIMIT_REACHED", "同一账号不能重复助力");
            return original(previous);
        }
        var now = LocalDateTime.now(businessClock).truncatedTo(ChronoUnit.MILLIS);
        if (!"ACTIVE".equals(root.status()) || !"RESERVED".equals(participationStatus)
                || !now.isBefore(root.helpExpiresAt()))
            throw conflict("BARGAIN_EXPIRED", "砍价已结束");
        var scope = mapper.scope(root.activitySkuId());
        String day = now.toLocalDate().toString();
        mapper.ensureHelpQuota(scope.activityId(), scope.activityCode(), helperUserId, day);
        if (mapper.useHelpQuota(scope.activityCode(), helperUserId, day, scope.dailyHelpLimit()) != 1)
            throw conflict("HELP_LIMIT_REACHED", "今日助力次数已用完");

        int step = root.helpCount() + 1;
        long cents = root.startAmount().subtract(root.targetAmount()).movePointRight(2).longValueExact();
        long decreaseCents = cents / root.requiredHelpers() + (step <= cents % root.requiredHelpers() ? 1 : 0);
        var decrease = BigDecimal.valueOf(decreaseCents, 2);
        var after = root.currentAmount().subtract(decrease);
        if (decreaseCents <= 0 || after.compareTo(root.targetAmount()) < 0)
            throw new IllegalStateException("砍价固定步进与进度不一致");
        boolean qualified = step == root.requiredHelpers();
        if (qualified && after.compareTo(root.targetAmount()) != 0)
            throw new IllegalStateException("最后一步未达到目标价");
        var purchaseUntil = qualified ? now.plusMinutes(15) : null;
        String status = qualified ? "QUALIFIED" : "ACTIVE";
        if (mapper.advance(bargainId, root.helpCount(), after, status, purchaseUntil) != 1)
            throw conflict("BARGAIN_CHANGED", "砍价进度已变化");
        if (qualified && mapper.qualifyParticipation(root.participationId(), purchaseUntil) != 1)
            throw conflict("BARGAIN_CHANGED", "参与资格已变化");
        mapper.record(bargainId, helperUserId, requestKey, step, root.currentAmount(), decrease, after, now);
        return result(bargainId, step, decrease, after, status, purchaseUntil);
    }

    private static BargainHelpVO result(long bargain, int step, BigDecimal decrease, BigDecimal after,
                                        String status, LocalDateTime purchaseUntil) {
        return new BargainHelpVO(bargain, step, decrease, after, step, status,
                purchaseUntil == null ? null : purchaseUntil.atOffset(ZoneOffset.ofHours(8)));
    }

    private static BargainHelpVO original(MarketingBargainHelpMapper.Help row) {
        boolean qualified = row.amountAfter().compareTo(row.targetAmount()) == 0;
        return result(row.bargainId(), row.stepNo(), row.decreaseAmount(), row.amountAfter(),
                qualified ? "QUALIFIED" : "ACTIVE", qualified ? row.helpedAt().plusMinutes(15) : null);
    }

    private static BusinessException conflict(String reason, String message) {
        return new BusinessException(409, message, Map.of("reason", reason), null);
    }
}
