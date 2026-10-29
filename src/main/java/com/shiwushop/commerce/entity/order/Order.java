package com.shiwushop.commerce.entity.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.commerce.entity.status.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long shopId;
    @TableField(exist = false) private Long skuId;
    @TableField(exist = false) private String productName;
    @TableField(exist = false) private String skuSpec;
    @TableField(exist = false) private Integer quantity;
    @TableField(exist = false) private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    @TableField(exist = false) private String consignee;
    @TableField(exist = false) private String phone;
    @TableField(exist = false) private String shippingAddress;
    private byte[] consigneeCiphertext;
    private byte[] phoneCiphertext;
    private String province;
    private String city;
    private String district;
    private byte[] detailCiphertext;
    private Integer shippingKeyVersion;
    private OrderStatus status;
    private LocalDateTime payExpiresAt;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
