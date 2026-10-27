package com.shiwushop.dto.user;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 100, message = "账号不得超过100个字符")
    private String username;
    @NotBlank(message = "密码不能为空")
    private String password;


    //@NotBlank(message = "请先完成滑块验证")
    private String captchaProof;

}
