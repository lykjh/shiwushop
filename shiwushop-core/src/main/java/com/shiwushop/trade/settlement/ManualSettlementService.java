package com.shiwushop.trade.settlement;

import com.shiwushop.trade.settlement.dto.SettlementRequests;
import com.shiwushop.trade.settlement.vo.SettlementView;

public interface ManualSettlementService {
    SettlementView.Candidates candidates(long adminId, long shopId, Long afterId, int size);

    SettlementView.Page adminList(long adminId, Long shopId, Long afterId, int size);

    SettlementView.Page merchantList(long userId, Long afterId, int size);

    SettlementView.Detail adminDetail(long adminId, long id);

    SettlementView.Detail merchantDetail(long userId, long id);

    SettlementView.Detail create(long adminId, SettlementRequests.Create request);

    SettlementView.Detail approve(long adminId, long id, SettlementRequests.Key request);

    SettlementView.Detail processing(long adminId, long id, SettlementRequests.Key request);

    SettlementView.Detail payment(long adminId, long id, SettlementRequests.Payment request);

    SettlementView.Detail voidSettlement(long adminId, long id, SettlementRequests.Void request);

    SettlementView.Detail correction(long adminId, long id, SettlementRequests.Correction request);

    SettlementView.Detail reviewAdjustment(long adminId, long id, long adjustmentId,
                                           SettlementRequests.Review request);
}
