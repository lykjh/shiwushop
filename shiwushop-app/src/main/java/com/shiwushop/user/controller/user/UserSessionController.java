package com.shiwushop.user.controller.user;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.user.dto.user.AccountProofDTO;
import com.shiwushop.user.service.impl.UserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users/sessions")
@RequiredArgsConstructor
public class UserSessionController {
    private final UserSessionService sessions;

    @GetMapping
    public Result<List<UserSessionService.SessionView>> list(HttpServletRequest request) {
        return Result.success(sessions.list(SessionUser.requireId(request), request.getSession(false)));
    }

    @PostMapping("/send-code")
    public Result<Void> sendCode(HttpServletRequest request) {
        sessions.sendCode(SessionUser.requireId(request), request.getRemoteAddr());
        return Result.success();
    }

    @PostMapping("/{sessionRef}/revoke")
    public Result<Void> revoke(@PathVariable String sessionRef, @Valid @RequestBody AccountProofDTO body,
                               HttpServletRequest request) {
        sessions.revoke(SessionUser.requireId(request), request.getSession(false), sessionRef, body);
        return Result.success();
    }

    @PostMapping("/revoke-others")
    public Result<Void> revokeOthers(@Valid @RequestBody AccountProofDTO body, HttpServletRequest request) {
        sessions.revokeOthers(SessionUser.requireId(request), request.getSession(false), body);
        return Result.success();
    }
}
