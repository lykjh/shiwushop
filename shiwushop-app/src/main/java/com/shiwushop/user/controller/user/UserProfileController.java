package com.shiwushop.user.controller.user;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.user.dto.user.UserProfileUpdateDTO;
import com.shiwushop.user.service.UserProfileService;
import com.shiwushop.user.vo.user.UserProfileVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users/profile")
@RequiredArgsConstructor
public class UserProfileController {
    private final UserProfileService profiles;

    /** 读取当前用户资料。 */
    @GetMapping
    public Result<UserProfileVO> get(HttpServletRequest request) {
        return Result.success(profiles.get(SessionUser.requireId(request)));
    }

    /** 整体替换当前用户资料。 */
    @PutMapping
    public Result<UserProfileVO> update(@Valid @RequestBody UserProfileUpdateDTO body, HttpServletRequest request) {
        return Result.success(profiles.update(SessionUser.requireId(request), body));
    }
}
