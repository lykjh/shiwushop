package com.shiwushop.member.service;

import java.util.List;

public interface MemberMaintenanceService {
    List<Long> uninitializedUsers();

    void initialize(Long userId);

    List<Long> usersWithExpiredPoints();

    void expirePoints(Long userId);
}
