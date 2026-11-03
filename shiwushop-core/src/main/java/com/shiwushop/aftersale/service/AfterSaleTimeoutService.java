package com.shiwushop.aftersale.service;

import com.shiwushop.aftersale.vo.timeout.AfterSaleTimeoutPageVO;
import com.shiwushop.aftersale.entity.timeout.AfterSaleTimeoutTask;
import com.shiwushop.aftersale.dto.timeout.CloseAfterSaleTimeoutDTO;

public interface AfterSaleTimeoutService {
    void scan();
    void onStageChanged(Long caseId);
    AfterSaleTimeoutTask detail(Long adminId, Long id);
    AfterSaleTimeoutTask claim(Long adminId, Long id);
    AfterSaleTimeoutTask close(Long adminId, Long id, CloseAfterSaleTimeoutDTO dto);
    AfterSaleTimeoutPageVO list(Long adminId, Long beforeId, int size);
}
