package com.shiwushop.aftersale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("after_sale_return_destinations")
public class ReturnDestinationRecord {
    /** 关联售后单 ID。 */
    @TableId(type = IdType.INPUT)
    private Long caseId;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 隐私数据加密密钥版本。 */
    private Integer keyVersion;
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
