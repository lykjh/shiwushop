package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.user.dto.user.UserProfileUpdateDTO;
import com.shiwushop.user.entity.user.UserProfile;
import com.shiwushop.user.mapper.user.UserProfileMapper;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.service.UserProfileService;
import com.shiwushop.user.vo.user.UserProfileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {
    private final UserProfileMapper profiles;
    private final AccountAccess accounts;
    private final MediaAssetAccess media;

    /** 只读取当前有效账号的资料。 */
    @Override
    public UserProfileVO get(Long userId) {
        accounts.requireActive(userId);
        UserProfile profile = profiles.selectById(userId);
        if (profile == null) throw new BusinessException(404, "用户资料不存在");
        return new UserProfileVO(profile.getNickname(), profile.getAvatarMediaId());
    }

    /** 媒体行锁持续至事务提交，两个资料字段由同一条语句整体替换。 */
    @Override
    @Transactional(timeout = 30)
    public UserProfileVO update(Long userId, UserProfileUpdateDTO request) {
        accounts.requireActive(userId);
        if (request.avatarMediaId() != null) media.requireReadyOwned(request.avatarMediaId(), userId);
        if (profiles.replaceProfile(userId, request.nickname(), request.avatarMediaId()) != 1)
            throw new BusinessException(404, "用户资料不存在");
        BusinessEventLog.committed("user", "profile_updated", "user", userId);
        return new UserProfileVO(request.nickname(), request.avatarMediaId());
    }
}
