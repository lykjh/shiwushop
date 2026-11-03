package com.shiwushop.payment.service;
import java.time.LocalDate;
/** 已配置支付宝客户端获取当日业务账单地址。 */
public interface BillDownloadGateway {
 String billDownloadUrl(LocalDate date);
 String appId();
 String sellerId();
 String channel();
}
