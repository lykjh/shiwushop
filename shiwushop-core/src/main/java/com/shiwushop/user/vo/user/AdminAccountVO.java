package com.shiwushop.user.vo.user;

import com.shiwushop.user.entity.status.AccountRole;
import com.shiwushop.user.entity.status.AccountStatus;

public record AdminAccountVO(
    /** 所属用户 ID。 */
    Long userId,
    /** 账号权限角色，与会员等级无关。 */
    AccountRole role,
    /** 账号状态：ACTIVE 正常、DISABLED 停用。 */
    AccountStatus status
) {
}
