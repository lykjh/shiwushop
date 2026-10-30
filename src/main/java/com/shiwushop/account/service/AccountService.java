package com.shiwushop.account.service;

import com.shiwushop.account.vo.user.UserInfoVO;

/** 当前账号的资料与密码维护。 */
public interface AccountService {
    /** 返回启用中账号的基本资料和角色。 */
    UserInfoVO currentUser(Long userId);

    /** 校验旧密码，更新密码和鉴权版本，使旧会话失效。 */
    void changePassword(Long userId, String oldPassword, String newPassword);
}
