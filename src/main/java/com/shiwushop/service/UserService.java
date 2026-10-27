package com.shiwushop.service;

import com.shiwushop.dto.user.LoginDTO;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.vo.user.LoginVO;
import com.shiwushop.vo.user.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;


public interface UserService {
    void register(RegisterDTO dto, HttpServletRequest request);

    LoginVO login(LoginDTO dto, HttpServletRequest request);

    UserInfoVO currentUser(Long userId);

    void changePassword(Long userId, String oldPassword, String newPassword);
}
