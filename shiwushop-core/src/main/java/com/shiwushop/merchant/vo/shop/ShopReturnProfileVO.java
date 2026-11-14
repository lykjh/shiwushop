package com.shiwushop.merchant.vo.shop;

public record ShopReturnProfileVO(
    /** 店铺退货联系人的姓名。 */
    String contactName,
    /** 店铺退货联系人的电话。 */
    String contactPhone,
    /** 收货人姓名。 */
    String consignee,
    /** 手机号。 */
    String phone,
    /** 地址所在省份。 */
    String province,
    /** 地址所在城市。 */
    String city,
    /** 地址所在区县。 */
    String district,
    /** 详细地址。 */
    String detail
) {
}
