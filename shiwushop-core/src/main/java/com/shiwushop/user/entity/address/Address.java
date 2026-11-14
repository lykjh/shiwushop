package com.shiwushop.user.entity.address;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("addresses")
public class Address {

    /** 收货地址 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID。 */
    private Long userId;
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
    /** 隐私数据加密密钥版本。 */
    private Integer keyVersion;
    /** 是否为默认收货地址，1 是、0 否。 */
    private Integer isDefault;
    /** 收货地址最近使用时间。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime lastUsedAt;
    /** 记录软删除时间。 */
    private LocalDateTime deletedAt;
    /** 收货人姓名；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String consignee;
    /** 手机号；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String phone;
    /** 完整地址文本；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String address;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
