package com.shiwushop.payment.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data @TableName("payment_bills")
public class PaymentBill {
 @TableId(type=IdType.AUTO) private Long id;
 private String channel, appId, sellerId, billType, sha256, storageKey, status;
 private LocalDate billDate;
 private Long byteSize, downloadedBy;
 private LocalDateTime downloadedAt;
}
