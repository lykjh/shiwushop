package com.shiwushop.user.service;

public interface AccountTradeGate {
    /** 调用方在创建新交易的同一事务中调用，已有订单履约路径不得调用。 */
    void requireCanStartTrade(Long userId);
}
