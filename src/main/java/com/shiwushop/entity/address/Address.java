package com.shiwushop.entity.address;

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

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private byte[] consigneeCiphertext;
    private byte[] phoneCiphertext;
    private String province;
    private String city;
    private String district;
    private byte[] detailCiphertext;
    private Integer keyVersion;
    private Integer isDefault;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime lastUsedAt;
    private LocalDateTime deletedAt;
    @TableField(exist = false)
    private String consignee;
    @TableField(exist = false)
    private String phone;
    @TableField(exist = false)
    private String address;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
