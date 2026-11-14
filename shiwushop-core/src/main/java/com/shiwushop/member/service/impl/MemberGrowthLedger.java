package com.shiwushop.member.service.impl;

import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MemberGrowthLedger {
    private final MemberMapper mapper;

    /** 账户锁内追加流水并重算等级，退款与人工调整共用同一余额/历史逻辑。 */
    public void record(MemberAccount account, MemberGrowthEntry entry, LocalDateTime now) {
        entry.setOldLevelCode(account.getLevelCode());
        account.setGrowthAmount(account.getGrowthAmount().add(entry.getDeltaAmount()));
        account.setLevelCode(MemberRewardPolicy.levelFor(account.getGrowthAmount(), mapper.levels()));
        entry.setBalanceAfter(account.getGrowthAmount());
        entry.setNewLevelCode(account.getLevelCode());
        mapper.insertGrowthEntry(entry);
        mapper.updateAccount(account);
        if (!entry.getOldLevelCode().equals(entry.getNewLevelCode()))
            mapper.levelChanged(entry, now);
    }
}
