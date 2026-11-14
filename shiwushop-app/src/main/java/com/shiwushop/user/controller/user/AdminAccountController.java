package com.shiwushop.user.controller.user;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.user.dto.user.AdminAccountStatusDTO;
import com.shiwushop.user.service.impl.AdminAccountQueryService;
import com.shiwushop.user.service.impl.AdminAccountStatusService;
import com.shiwushop.user.vo.user.AdminAccountVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminAccountController {
    private final AdminAccountQueryService accounts;
    private final AdminAccountStatusService statuses;

    /** 平台管理员按 ID 查询最小账号状态。 */
    @GetMapping("/{userId}")
    public Result<AdminAccountVO> detail(@PathVariable @Positive Long userId, HttpServletRequest request) {
        return Result.success(accounts.detail(SessionUser.requireId(request), userId));
    }

    @PutMapping("/{userId}/status")
    public Result<AdminAccountVO> changeStatus(@PathVariable @Positive Long userId,
                                               @Valid @RequestBody AdminAccountStatusDTO body,
                                               HttpServletRequest request) {
        return Result.success(statuses.change(SessionUser.requireId(request), userId,
                body.status(), body.reason().trim()));
    }
}
