package com.shiwushop.merchant.vo.shop;

public record ReturnDestinationSnapshot(
    /** 所属店铺 ID。 */
    Long shopId,
    /** 隐私数据加密密钥版本。 */
    int keyVersion,
    /** 收货人姓名密文。 */
    byte[] consigneeCiphertext,
    /** 手机号密文。 */
    byte[] phoneCiphertext,
    /** 地址所在省份。 */
    String province,
    /** 地址所在城市。 */
    String city,
    /** 地址所在区县。 */
    String district,
    /** 详细地址密文。 */
    byte[] detailCiphertext
) {
}
