package com.shiwushop.user.vo.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginVO {

    /** 当前记录 ID。 */
    private Long id;
    /** 登录用户名。 */
    private String username;
}
