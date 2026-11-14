package com.shiwushop.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("payment_bills")
public class PaymentBill {
    /** 渠道账单 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    private String channel, // 支付渠道。
        appId, // 支付渠道应用标识。
        sellerId, // 支付渠道收款商户标识。
        billType, // 渠道账单类型。
        sha256, // 文件内容的 SHA-256 摘要。
        storageKey, // 文件内部存储定位键。
        status; // 账单处理状态；当前下载成功为 DOWNLOADED_UNPARSED，不表示已完成对账。
    /** 支付渠道账单所属日期。 */
    private LocalDate billDate;
    private Long byteSize, // 文件大小，单位字节。
        downloadedBy; // 发起账单下载的用户 ID。
    /** 账单下载时间。 */
    private LocalDateTime downloadedAt;
}
