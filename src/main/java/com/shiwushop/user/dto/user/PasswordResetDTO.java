package com.shiwushop.user.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 找回密码只接收已签发的票据和新密码；目标账号由服务器保存的验证结果决定。 */
public record PasswordResetDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "重设凭证格式错误") String resetTicket,
        @NotBlank @Size(min = 8, max = 72, message = "密码长度须为8至72位")
        @Pattern(regexp = "[!-~]*", message = "密码只能包含字母、数字和特定符号") String newPassword) {}
