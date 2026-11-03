package com.shiwushop.payment.vo;
import com.shiwushop.payment.entity.PaymentBill;
import java.time.LocalDate;
import java.time.LocalDateTime;
public record PaymentBillVO(Long id,String channel,String appId,String sellerId,String billType,LocalDate billDate,
 String sha256,Long byteSize,String status,Long downloadedBy,LocalDateTime downloadedAt) {
 public static PaymentBillVO from(PaymentBill b) { return new PaymentBillVO(b.getId(),b.getChannel(),b.getAppId(),b.getSellerId(),b.getBillType(),b.getBillDate(),b.getSha256(),b.getByteSize(),b.getStatus(),b.getDownloadedBy(),b.getDownloadedAt()); }
}
