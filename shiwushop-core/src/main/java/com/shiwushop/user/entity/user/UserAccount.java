package com.shiwushop.user.entity.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.user.entity.status.AccountRole;
import com.shiwushop.user.entity.status.AccountStatus;
import lombok.Data;

@Data
@TableName("user_accounts")
public class UserAccount {
    /** 用户主键，同时关联用户主体记录。 */
    @TableId(type = IdType.INPUT)
    private Long userId;
    /** 登录用户名。 */
    private String username;
    /** 密码哈希，不保存明文密码。 */
    private String passwordHash;
    /** 账号权限角色，与会员等级无关。 */
    private AccountRole role;
    /** 账号状态：ACTIVE 正常、DISABLED 停用。 */
    private AccountStatus status;
    /** 账号认证版本，用于使旧认证缓存和会话失效。 */
    private Integer authVersion;
}
