package com.shiwushop.member.service;

import com.shiwushop.member.model.MemberAccount;
import com.shiwushop.member.vo.MemberGrowthEntryVO;
import com.shiwushop.member.vo.MemberPointEntryVO;
import com.shiwushop.member.vo.MemberPointLotPage;
import com.shiwushop.member.vo.MemberRewardTaskPage;
import com.shiwushop.member.vo.MemberOverview;
import com.shiwushop.member.vo.AdminGrowthEntryPage;
import com.shiwushop.member.vo.AdminPointEntryPage;

import java.time.LocalDateTime;
import java.util.List;

public interface MemberAccountService {
    List<com.shiwushop.member.vo.MemberLevelVO> levels();

    com.shiwushop.member.vo.MemberLevelVO lockLevel(String code);

    MemberAccount initializeAndLock(Long userId);

    MemberOverview overview(Long userId);

    MemberOverview adminOverview(Long actorId, Long userId);

    AdminGrowthEntryPage adminGrowthEntries(Long actorId, Long userId, Long afterId, int size, Long orderId);

    AdminPointEntryPage adminPointEntries(Long actorId, Long userId, Long afterId, int size, Long orderId);

    List<MemberGrowthEntryVO> growthEntries(Long userId, Long beforeId, int limit);

    List<MemberPointEntryVO> pointEntries(Long userId, Long beforeId, int limit);

    MemberPointLotPage pointLots(Long userId, Long afterId, int size, String status);

    MemberRewardTaskPage rewardTasks(Long userId, Long afterId, int size, String kind, String status, Long orderId);

    String levelAt(Long userId, LocalDateTime at);
}
