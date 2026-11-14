package com.shiwushop.merchant.entity.shop;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("shop_return_profiles")
public class ShopReturnProfile {
    /** 所属店铺 ID。 */
    @TableId(type = IdType.INPUT)
    private Long shopId;
    /** 隐私数据加密密钥版本。 */
    private Integer keyVersion;
    /** 退货联系人姓名密文。 */
    private byte[] contactNameCiphertext;
    /** 退货联系人电话密文。 */
    private byte[] contactPhoneCiphertext;
    /** 收货人姓名密文。 */
    private byte[] consigneeCiphertext;
    /** 手机号密文。 */
    private byte[] phoneCiphertext;
    /** 地址所在省份。 */
    private String province;
    /** 地址所在城市。 */
    private String city;
    /** 地址所在区县。 */
    private String district;
    /** 详细地址密文。 */
    private byte[] detailCiphertext;
}
