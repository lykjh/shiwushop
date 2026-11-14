package com.shiwushop.user.controller.user;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.user.dto.user.AccountProofDTO;
import com.shiwushop.user.mapper.user.AccountDeletionRequestMapper;
import com.shiwushop.user.service.impl.AccountDeletionRequestService;
import com.shiwushop.user.service.impl.AccountProofService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/users/deletion-request")
@ConditionalOnProperty(name = "shop.account.deletion-request-enabled", havingValue = "true")
@RequiredArgsConstructor
public class AccountDeletionRequestController {
    /** 申请注销账号时二次验证凭据的用途标识。 */
    private static final String PURPOSE = "account-deletion";
    private final AccountDeletionRequestService requests;
    private final AccountProofService proof;

    public record RequestView(
        /** 注销申请状态：PENDING 待处理、WITHDRAWN 已撤回。 */
        String status,
        /** 请求发起时间。 */
        LocalDateTime requestedAt,
        /** 账号注销申请的处理期限。 */
        LocalDateTime deadlineAt,
        /** 注销申请撤回时间。 */
        LocalDateTime withdrawnAt
    ) {
    }

    @GetMapping
    public Result<RequestView> latest(HttpServletRequest request) {
        var latest = requests.latest(SessionUser.requireId(request));
        if (latest == null)
            throw new BusinessException(404, "没有注销申请");
        return Result.success(view(latest));
    }

    @PostMapping("/send-code")
    public Result<Void> sendCode(HttpServletRequest request) {
        proof.sendCode(SessionUser.requireId(request), PURPOSE, request.getRemoteAddr());
        return Result.success();
    }

    @PostMapping
    public Result<RequestView> create(@Valid @RequestBody AccountProofDTO body, HttpServletRequest request) {
        var identity = SessionUser.require(request);
        proof.verify(identity.userId(), PURPOSE, body.currentPassword(), body.smsVerification());
        return Result.success(view(requests.request(identity.userId(), identity.authVersion())));
    }

    @PostMapping("/withdraw")
    public Result<RequestView> withdraw(@Valid @RequestBody AccountProofDTO body, HttpServletRequest request) {
        var identity = SessionUser.require(request);
        proof.verify(identity.userId(), PURPOSE, body.currentPassword(), body.smsVerification());
        return Result.success(view(requests.withdraw(identity.userId(), identity.authVersion())));
    }


    private static RequestView view(AccountDeletionRequestMapper.Request request) {
        return new RequestView(request.status(), request.requestedAt(), request.deadlineAt(), request.withdrawnAt());
    }
}
