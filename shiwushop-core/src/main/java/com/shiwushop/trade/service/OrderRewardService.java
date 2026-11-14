package com.shiwushop.trade.service;

public interface OrderRewardService {
    void reconcile(Long orderId, Long taskId, String claimToken);
}
