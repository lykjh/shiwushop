package com.shiwushop.merchant.entity.shop;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("shop_return_profiles")
public class ShopReturnProfile {
    @TableId(type = IdType.INPUT)
    private Long shopId;
    private Integer keyVersion;
    private byte[] contactNameCiphertext;
    private byte[] contactPhoneCiphertext;
    private byte[] consigneeCiphertext;
    private byte[] phoneCiphertext;
    private String province;
    private String city;
    private String district;
    private byte[] detailCiphertext;
}
