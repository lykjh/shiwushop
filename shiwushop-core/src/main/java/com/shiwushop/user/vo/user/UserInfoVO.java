package com.shiwushop.user.vo.user;

public record UserInfoVO(
    /** 当前记录 ID。 */
    Long id,
    /** 登录用户名。 */
    String username,
    /** 账号权限角色，与会员等级无关。 */
    String role,
    /** 脱敏手机号。 */
    String phoneMasked
) {
}
