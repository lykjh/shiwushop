package com.shiwushop.payment.service;

import com.shiwushop.payment.vo.PaymentBillVO;
import org.springframework.core.io.ByteArrayResource;

import java.time.LocalDate;

/** 下载渠道账单并提供已有批次和原始文件。 */
public interface PaymentBillService {
    PaymentBillVO download(LocalDate date, Long actor);

    PaymentBillVO detail(Long id);

    ByteArrayResource file(Long id);
}
