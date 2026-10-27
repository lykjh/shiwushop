package com.shiwushop.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RegisterDTO {

    @NotBlank(message = "账号不能为空")
    @Size(min=4,max=100,message = "账号长度在4到100个字符之间")
    @Pattern(regexp = "[a-zA-Z0-9._-]+",message = "账号只能包含字母、数字、点、下划线和连字符")
    private String username;
    @NotBlank(message = "密码不能为空")
    @Size(min=8,max=72,message = "密码长度在8到72个字符之间")
    private String password;

    // 注册时提交当前 Session 的一次性滑块凭证。
    private String captchaProof;

}
