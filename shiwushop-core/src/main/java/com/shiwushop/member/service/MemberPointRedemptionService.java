package com.shiwushop.member.service;

public interface MemberPointRedemptionService {
    long spend(long userId, long orderId, long units);

    long returnSpent(long userId, long orderId, Long afterSaleCaseId, long units,
                     String returnKey, boolean platformFailure);
}
