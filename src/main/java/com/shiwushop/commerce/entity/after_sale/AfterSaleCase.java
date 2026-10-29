package com.shiwushop.commerce.entity.after_sale;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.entity.status.AfterSaleStatus;
import com.shiwushop.entity.status.AfterSaleType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("after_sale_cases")
public class AfterSaleCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String requestKey;
    private AfterSaleType type;
    private AfterSaleStatus status;
    private String reason;
    private BigDecimal refundAmount;
    private Long reviewedByUserId;
    private LocalDateTime reviewedAt;
    private String reviewReason;
    private LocalDateTime requestedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
