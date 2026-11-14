package com.shiwushop.trade.controller.settlement;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.trade.settlement.ManualSettlementService;
import com.shiwushop.trade.settlement.dto.SettlementRequests;
import com.shiwushop.trade.settlement.vo.SettlementView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ManualSettlementController {
    private final ManualSettlementService settlements;

    @GetMapping("/admin/manual-settlements/candidates")
    public Result<SettlementView.Candidates> candidates(@RequestParam @Positive long shopId,
                                                        @RequestParam(required = false) Long afterId,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        HttpServletRequest request) {
        return Result.success(settlements.candidates(SessionUser.requireId(request), shopId, afterId, size));
    }

    @GetMapping("/admin/manual-settlements")
    public Result<SettlementView.Page> adminList(@RequestParam(required = false) Long shopId,
                                                 @RequestParam(required = false) Long afterId,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 HttpServletRequest request) {
        return Result.success(settlements.adminList(SessionUser.requireId(request), shopId, afterId, size));
    }

    @PostMapping("/admin/manual-settlements")
    public Result<SettlementView.Detail> create(@Valid @RequestBody SettlementRequests.Create body,
                                                HttpServletRequest request) {
        return Result.success(settlements.create(SessionUser.requireId(request), body));
    }

    @GetMapping("/admin/manual-settlements/{id}")
    public Result<SettlementView.Detail> adminDetail(@PathVariable @Positive long id,
                                                     HttpServletRequest request) {
        return Result.success(settlements.adminDetail(SessionUser.requireId(request), id));
    }

    @PostMapping("/admin/manual-settlements/{id}/approve")
    public Result<SettlementView.Detail> approve(@PathVariable @Positive long id,
                                                 @Valid @RequestBody SettlementRequests.Key body,
                                                 HttpServletRequest request) {
        return Result.success(settlements.approve(SessionUser.requireId(request), id, body));
    }

    @PostMapping("/admin/manual-settlements/{id}/processing")
    public Result<SettlementView.Detail> processing(@PathVariable @Positive long id,
                                                    @Valid @RequestBody SettlementRequests.Key body,
                                                    HttpServletRequest request) {
        return Result.success(settlements.processing(SessionUser.requireId(request), id, body));
    }

    @PostMapping("/admin/manual-settlements/{id}/payment")
    public Result<SettlementView.Detail> payment(@PathVariable @Positive long id,
                                                 @Valid @RequestBody SettlementRequests.Payment body,
                                                 HttpServletRequest request) {
        return Result.success(settlements.payment(SessionUser.requireId(request), id, body));
    }

    @PostMapping("/admin/manual-settlements/{id}/void")
    public Result<SettlementView.Detail> voidSettlement(@PathVariable @Positive long id,
                                                        @Valid @RequestBody SettlementRequests.Void body,
                                                        HttpServletRequest request) {
        return Result.success(settlements.voidSettlement(SessionUser.requireId(request), id, body));
    }

    @PostMapping("/admin/manual-settlements/{id}/corrections")
    public Result<SettlementView.Detail> correction(@PathVariable @Positive long id,
                                                    @Valid @RequestBody SettlementRequests.Correction body,
                                                    HttpServletRequest request) {
        return Result.success(settlements.correction(SessionUser.requireId(request), id, body));
    }

    @PostMapping("/admin/manual-settlements/{id}/adjustments/{adjustmentId}/review")
    public Result<SettlementView.Detail> reviewAdjustment(@PathVariable @Positive long id,
                                                          @PathVariable @Positive long adjustmentId,
                                                          @Valid @RequestBody SettlementRequests.Review body,
                                                          HttpServletRequest request) {
        return Result.success(settlements.reviewAdjustment(SessionUser.requireId(request), id, adjustmentId, body));
    }

    @GetMapping("/merchant/manual-settlements")
    public Result<SettlementView.Page> merchantList(@RequestParam(required = false) Long afterId,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest request) {
        return Result.success(settlements.merchantList(SessionUser.requireId(request), afterId, size));
    }

    @GetMapping("/merchant/manual-settlements/{id}")
    public Result<SettlementView.Detail> merchantDetail(@PathVariable @Positive long id,
                                                        HttpServletRequest request) {
        return Result.success(settlements.merchantDetail(SessionUser.requireId(request), id));
    }
}
