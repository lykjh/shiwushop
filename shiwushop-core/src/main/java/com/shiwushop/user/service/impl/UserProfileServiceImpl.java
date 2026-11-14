package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.common.privacy.PrivacyCipher;
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
    private final PrivacyCipher privacy;

    /** 只读取当前有效账号的资料。 */
    @Override
    public UserProfileVO get(Long userId) {
        accounts.requireActive(userId);
        UserProfile profile = profiles.selectById(userId);
        if (profile == null)
            throw new BusinessException(404, "用户资料不存在");
        return new UserProfileVO(profile.getNickname(), profile.getAvatarMediaId(), email(userId));
    }

    /** 昵称头像整体替换；邮箱只在明确提供时改写，保护旧客户端已保存的邮箱。 */
    @Override
    @Transactional(timeout = 30)
    public UserProfileVO update(Long userId, UserProfileUpdateDTO request) {
        accounts.requireActive(userId);
        if (request.avatarMediaId() != null)
            media.requireReadyOwned(request.avatarMediaId(), userId);
        if (profiles.replaceProfile(userId, request.nickname(), request.avatarMediaId()) != 1)
            throw new BusinessException(404, "用户资料不存在");
        if (request.emailSpecified())
            profiles.replaceEmail(userId, request.email() == null ? null : privacy.encrypt(userId, "profile-email", request.email()));
        BusinessEventLog.committed("user", "profile_updated", "user", userId);
        return new UserProfileVO(request.nickname(), request.avatarMediaId(),
                request.emailSpecified() ? request.email() : email(userId));
    }

    /** 邮箱只供本人读取，密文与账号和字段绑定。 */
    private String email(Long userId) {
        UserProfileMapper.EmailPrivate stored = profiles.findEmail(userId);
        return stored == null || stored.getCiphertext() == null ? null
                : privacy.decrypt(userId, "profile-email", stored.getKeyVersion(), stored.getCiphertext());
    }
}
