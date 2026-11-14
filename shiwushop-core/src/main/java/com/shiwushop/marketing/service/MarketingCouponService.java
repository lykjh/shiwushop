package com.shiwushop.marketing.service;

import com.shiwushop.marketing.dto.*;
import com.shiwushop.marketing.vo.*;

import java.util.List;

public interface MarketingCouponService {
    CouponGrantVO claim(Long user, Long activity, String key);

    CouponGrantVO redeem(Long user, String key, CouponRedemption request);

    CouponGrantVO compensate(Long actor, Long activity, String key, CompensationGrant request);

    List<CouponCodeVO> createCodes(Long actor, Long activity, String key, CouponCodeBatch request);

    CouponCodeVO disableCode(Long actor, Long id, ActivityReason reason);

    CouponCodeVO.Page codes(Long actor, Long activity, Long afterId, int size, String status);

    CouponVO.Page coupons(Long user, Long afterId, int size, String status, Long shop);

    CouponGrantVO.Page grants(Long user, Long afterId, int size, String kind);

    List<Long> expiredCoupons();

    void expire(Long id);
}
