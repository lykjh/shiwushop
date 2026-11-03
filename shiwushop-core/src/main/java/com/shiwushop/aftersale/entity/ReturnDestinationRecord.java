package com.shiwushop.aftersale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("after_sale_return_destinations")
public class ReturnDestinationRecord {
    @TableId(type = IdType.INPUT)
    private Long caseId;
    private Long shopId;
    private Integer keyVersion;
    private byte[] consigneeCiphertext;
    private byte[] phoneCiphertext;
    private String province;
    private String city;
    private String district;
    private byte[] detailCiphertext;
}
