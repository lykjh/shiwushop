package com.shiwushop.merchant.vo.staff;

import java.util.Set;

public record ShopStaffVO(
    /** 所属店铺 ID。 */
    Long shopId,
    /** 所属用户 ID。 */
    Long userId,
    /** 店铺员工的授权角色列表。 */
    Set<String> roles,
    /** 员工授权状态：ACTIVE 有效、REVOKED 已撤销。 */
    String status,
    /** 并发更新版本号。 */
    Long version,
    /** 授予员工权限的用户 ID。 */
    Long grantedByUserId,
    /** 撤销员工权限的用户 ID。 */
    Long revokedByUserId
) {
}
