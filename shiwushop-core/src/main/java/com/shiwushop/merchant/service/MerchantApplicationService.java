package com.shiwushop.merchant.service;

import com.shiwushop.merchant.dto.application.MerchantApplicationDecisionDTO;
import com.shiwushop.merchant.dto.application.MerchantApplicationSaveDTO;
import com.shiwushop.merchant.vo.application.MerchantApplicationVO;

import java.util.List;

public interface MerchantApplicationService {
    MerchantApplicationVO create(Long applicantId, MerchantApplicationSaveDTO request);
    MerchantApplicationVO update(Long applicantId, Long id, MerchantApplicationSaveDTO request);
    MerchantApplicationVO submit(Long applicantId, Long id);
    MerchantApplicationVO withdraw(Long applicantId, Long id);
    MerchantApplicationVO mine(Long applicantId, Long id);
    MerchantApplicationVO decide(Long reviewerId, Long id, MerchantApplicationDecisionDTO request);
    List<MerchantApplicationVO> pending();
    Long reviewMediaId(Long applicationId, String documentType);
}
