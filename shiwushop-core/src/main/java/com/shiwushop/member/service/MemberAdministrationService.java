package com.shiwushop.member.service;

import com.shiwushop.member.dto.GrowthAdjustment;
import com.shiwushop.member.vo.GrowthAdjustmentResult;
import com.shiwushop.member.vo.PointAdjustmentResult;
import com.shiwushop.member.dto.PointAdjustment;

public interface MemberAdministrationService {
    GrowthAdjustmentResult adjustGrowth(Long actorId, Long userId, String requestKey, GrowthAdjustment request);

    PointAdjustmentResult adjustPoints(Long actorId, Long userId, String requestKey, PointAdjustment request);
}
