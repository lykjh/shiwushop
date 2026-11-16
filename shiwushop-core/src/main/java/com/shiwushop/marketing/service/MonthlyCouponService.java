package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.MonthlyBenefitVO;

import java.time.YearMonth;
import java.util.List;

/** 管理会员月券的查询、定时发放和人工重试。 */
public interface MonthlyCouponService {
    boolean enabled();

    YearMonth firstMonth();

    List<Long> missing(YearMonth month, int limit);

    List<Long> due();

    void establish(long user, YearMonth month);

    String acquire(long id);

    void deliver(long id, String token);

    void attention(long id, String token, String error);

    MonthlyBenefitVO benefit(long user, YearMonth month);

    MonthlyBenefitVO.Page admin(long actor, Long user, String state, String source, Long after, int size);

    MonthlyBenefitVO.Retry retry(long actor, long id, String reason);
}
