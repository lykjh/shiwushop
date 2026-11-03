package com.shiwushop.user.service;

import com.shiwushop.user.dto.user.UserProfileUpdateDTO;
import com.shiwushop.user.vo.user.UserProfileVO;

public interface UserProfileService {
    UserProfileVO get(Long userId);
    UserProfileVO update(Long userId, UserProfileUpdateDTO request);
}
