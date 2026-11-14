package com.shiwushop.user.vo.address;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AddressVO {

    /** 收货地址 ID。 */
    private Long id;
    /** 收货人姓名。 */
    private String consignee;
    /** 手机号。 */
    private String phone;
    /** 地址所在省份。 */
    private String province;
    /** 地址所在城市。 */
    private String city;
    /** 地址所在区县。 */
    private String district;
    /** 详细地址。 */
    private String detail;
    /** 是否为默认收货地址，1 是、0 否。 */
    private Integer isDefault;
}
