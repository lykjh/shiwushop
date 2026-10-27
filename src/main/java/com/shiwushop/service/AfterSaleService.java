package com.shiwushop.service;

import com.shiwushop.dto.after_sale.AfterSaleCreateDTO;
import com.shiwushop.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.dto.after_sale.ReturnReceiptDTO;
import com.shiwushop.dto.after_sale.ReturnShipmentDTO;
import com.shiwushop.vo.after_sale.AfterSaleVO;
import com.shiwushop.vo.after_sale.AfterSaleSliceVO;

public interface AfterSaleService {
    AfterSaleVO create(Long buyerId, Long orderId, String requestKey, AfterSaleCreateDTO request);
    AfterSaleSliceVO buyerList(Long buyerId, Long orderId, Long beforeId, int size);
    AfterSaleSliceVO merchantList(Long merchantId, Long shopId, Long beforeId, String status, int size);
    AfterSaleVO buyerDetail(Long buyerId, Long caseId);
    AfterSaleVO merchantDetail(Long merchantId, Long shopId, Long caseId);
    AfterSaleVO review(Long merchantId, Long shopId, Long caseId, AfterSaleReviewDTO request);
    AfterSaleVO sendReturn(Long buyerId, Long caseId, ReturnShipmentDTO request);
    AfterSaleVO receiveReturn(Long merchantId, Long shopId, Long caseId, ReturnReceiptDTO request);
    AfterSaleVO refund(Long merchantId, Long shopId, Long caseId);
    AfterSaleVO syncRefund(Long merchantId, Long shopId, Long caseId);
    void reconcileUnknown();
}
