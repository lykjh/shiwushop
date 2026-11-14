package com.shiwushop.merchant.entity.staff;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShopStaffGrant {
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 所属用户 ID。 */
    private Long userId;
    /** 是否授予商品目录管理权限，1 是、0 否。 */
    private Integer canCatalog;
    /** 是否授予库存管理权限，1 是、0 否。 */
    private Integer canInventory;
    /** 是否授予履约管理权限，1 是、0 否。 */
    private Integer canFulfillment;
    /** 员工授权状态：ACTIVE 有效、REVOKED 已撤销。 */
    private String status;
    /** 并发更新版本号。 */
    private Long version;
    /** 授予员工权限的用户 ID。 */
    private Long grantedByUserId;
    /** 员工权限授予时间。 */
    private LocalDateTime grantedAt;
    /** 撤销员工权限的用户 ID。 */
    private Long revokedByUserId;
    /** 员工权限撤销时间。 */
    private LocalDateTime revokedAt;
}
