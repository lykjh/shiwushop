package com.shiwushop.user.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginDTO {
    /** 登录用户名。 */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 100, message = "账号不得超过100个字符")
    private String username;
    /** 用户提交的明文密码，仅用于当前认证或注册处理。 */
    @NotBlank(message = "密码不能为空")
    private String password;

    //@NotBlank(message = "请先完成滑块验证")
    /** 与当前会话绑定的一次性图形验证码通过凭据。 */
    private String captchaProof;
}
