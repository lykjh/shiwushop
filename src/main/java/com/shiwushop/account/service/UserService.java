package com.shiwushop.account.service;

import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;


public interface UserService {
    void register(RegisterDTO dto, HttpServletRequest request);

    LoginVO login(LoginDTO dto, HttpServletRequest request);

    UserInfoVO currentUser(Long userId);

    void changePassword(Long userId, String oldPassword, String newPassword);
}
