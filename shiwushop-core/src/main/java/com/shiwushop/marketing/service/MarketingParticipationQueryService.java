package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.ParticipationPage;
import com.shiwushop.marketing.vo.PointRedemptionPage;

public interface MarketingParticipationQueryService {
    ParticipationPage participations(long user, Long afterId, int size, String type, String status);

    PointRedemptionPage redemptions(long user, Long afterId, int size, String status);
}
