package com.shiwushop.merchant.dto.staff;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record ShopStaffGrantDTO(
    /** 店铺员工的授权角色列表。 */
    @NotEmpty Set<String> roles
) {
}
