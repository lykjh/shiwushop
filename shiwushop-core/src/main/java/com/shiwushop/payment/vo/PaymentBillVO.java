package com.shiwushop.payment.vo;

import com.shiwushop.payment.entity.PaymentBill;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PaymentBillVO(
    /** 渠道账单 ID。 */
    Long id,
    /** 支付渠道。 */
    String channel,
    /** 支付渠道应用标识。 */
    String appId,
    /** 支付渠道收款商户标识。 */
    String sellerId,
    /** 渠道账单类型。 */
    String billType,
    /** 支付渠道账单所属日期。 */
    LocalDate billDate,
    /** 文件内容的 SHA-256 摘要。 */
    String sha256,
    /** 文件大小，单位字节。 */
    Long byteSize,
    /** 账单处理状态；DOWNLOADED_UNPARSED 表示已下载、尚未解析。 */
    String status,
    /** 发起账单下载的用户 ID。 */
    Long downloadedBy,
    /** 账单下载时间。 */
    LocalDateTime downloadedAt
) {
    public static PaymentBillVO from(PaymentBill b) {
        return new PaymentBillVO(b.getId(), b.getChannel(), b.getAppId(), b.getSellerId(), b.getBillType(),
                b.getBillDate(), b.getSha256(), b.getByteSize(), b.getStatus(), b.getDownloadedBy(),
                b.getDownloadedAt());
    }
}
