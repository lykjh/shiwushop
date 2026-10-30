package com.shiwushop.account.service;

import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;


public interface UserService {
    /** 验证注册入口和滑块凭证，创建资料与账号；成功后由调用方记录注册结果。 */
    void register(RegisterDTO dto, HttpServletRequest request);

    /** 校验账号、密码和风险条件，成功后建立当前请求的登录 Session。 */
    LoginVO login(LoginDTO dto, HttpServletRequest request);

    /** 返回启用中账号的基本资料和角色；账号已失效时要求重新登录。 */
    UserInfoVO currentUser(Long userId);

    /** 校验旧密码并更新新密码及鉴权版本，使旧会话失效。 */
    void changePassword(Long userId, String oldPassword, String newPassword);
}
