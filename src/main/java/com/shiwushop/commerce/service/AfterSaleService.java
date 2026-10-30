package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.after_sale.AfterSaleCreateDTO;
import com.shiwushop.commerce.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnReceiptDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnShipmentDTO;
import com.shiwushop.commerce.vo.after_sale.AfterSaleVO;
import com.shiwushop.commerce.vo.after_sale.AfterSaleSliceVO;

public interface AfterSaleService {
    /** 买家凭幂等键对已购订单项创建售后申请。 */
    AfterSaleVO create(Long buyerId, Long orderId, String requestKey, AfterSaleCreateDTO request);
    /** 按游标列出买家指定订单的售后申请。 */
    AfterSaleSliceVO buyerList(Long buyerId, Long orderId, Long beforeId, int size);
    /** 按状态和游标列出商家店铺的售后申请。 */
    AfterSaleSliceVO merchantList(Long merchantId, Long shopId, Long beforeId, String status, int size);
    /** 买家查看本人售后申请详情。 */
    AfterSaleVO buyerDetail(Long buyerId, Long caseId);
    /** 商家查看所属店铺售后申请详情。 */
    AfterSaleVO merchantDetail(Long merchantId, Long shopId, Long caseId);
    /** 商家审核买家的售后申请。 */
    AfterSaleVO review(Long merchantId, Long shopId, Long caseId, AfterSaleReviewDTO request);
    /** 买家登记退货物流信息。 */
    AfterSaleVO sendReturn(Long buyerId, Long caseId, ReturnShipmentDTO request);
    /** 商家登记退货签收情况。 */
    AfterSaleVO receiveReturn(Long merchantId, Long shopId, Long caseId, ReturnReceiptDTO request);
    /** 商家为符合条件的申请发起退款。 */
    AfterSaleVO refund(Long merchantId, Long shopId, Long caseId);
    /** 主动查询网关并同步一笔退款的最新状态。 */
    AfterSaleVO syncRefund(Long merchantId, Long shopId, Long caseId);
    /** 后台任务重查网关结果未知的退款尝试。 */
    void reconcileUnknown();
}
