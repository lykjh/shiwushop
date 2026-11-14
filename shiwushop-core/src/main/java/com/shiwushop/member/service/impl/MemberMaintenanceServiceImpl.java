package com.shiwushop.member.service.impl;

import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.MemberMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberMaintenanceServiceImpl implements MemberMaintenanceService {
    private final MemberMapper mapper;
    private final MemberAccountService accounts;
    private final MemberPointLedger points;
    private final Clock businessClock;

    public List<Long> uninitializedUsers() {
        return mapper.uninitializedUsers();
    }

    @Transactional(timeout = 20)
    public void initialize(Long userId) {
        accounts.initializeAndLock(userId);
    }

    public List<Long> usersWithExpiredPoints() {
        return mapper.usersWithExpiredPoints(LocalDateTime.now(businessClock));
    }

    @Transactional(timeout = 20)
    public void expirePoints(Long userId) {
        var account = accounts.initializeAndLock(userId);
        var now = LocalDateTime.now(businessClock);
        for (var lot : mapper.expiredLots(userId, now))
            points.expire(account, lot, null, now);
    }
}
