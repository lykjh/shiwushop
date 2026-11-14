package com.shiwushop.member.service;

import com.shiwushop.member.dto.RewardTaskCandidate;

import java.util.List;

public interface MemberTaskService {
    com.shiwushop.member.vo.AdminRewardTaskPage adminTasks(Long actorId, Long userId, Long afterId, int size,
                                                           String kind, String status, Long orderId);

    com.shiwushop.member.vo.TaskRetryResult retry(Long actorId, Long taskId, String reason);

    List<RewardTaskCandidate> pending();

    boolean claim(Long taskId, String token);

    boolean ownsClaim(Long taskId, String token);

    void release(Long taskId, String token, String errorCode);
}
