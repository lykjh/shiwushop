package com.shiwushop.aftersale.controller;

import com.shiwushop.aftersale.service.AfterSaleTimeoutService;
import com.shiwushop.aftersale.entity.timeout.AfterSaleTimeoutTask;
import com.shiwushop.aftersale.dto.timeout.CloseAfterSaleTimeoutDTO;
import jakarta.validation.Valid;
import com.shiwushop.aftersale.vo.timeout.AfterSaleTimeoutPageVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/after-sale-timeouts")
@RequiredArgsConstructor
public class AdminAfterSaleTimeoutController {
    private final AfterSaleTimeoutService service;

    /** 管理员按游标读取售后超时待办。 */
    @GetMapping
    public Result<AfterSaleTimeoutPageVO> list(@RequestParam(required = false) @Positive Long beforeId,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                               HttpServletRequest request) {
        return Result.success(service.list(SessionUser.requireId(request), beforeId, size));
    }
    /** 管理员查看单条超时待办。 */
    @GetMapping("/{id}")
    public Result<AfterSaleTimeoutTask> detail(@PathVariable @Positive Long id, HttpServletRequest request) {
        return Result.success(service.detail(SessionUser.requireId(request), id));
    }

    /** 管理员领取待办。 */
    @PostMapping("/{id}/claim")
    public Result<AfterSaleTimeoutTask> claim(@PathVariable @Positive Long id, HttpServletRequest request) {
        return Result.success(service.claim(SessionUser.requireId(request), id));
    }

    /** 经办人以证据和结论关闭待办。 */
    @PostMapping("/{id}/close")
    public Result<AfterSaleTimeoutTask> close(@PathVariable @Positive Long id, @Valid @RequestBody CloseAfterSaleTimeoutDTO dto,
                                               HttpServletRequest request) {
        return Result.success(service.close(SessionUser.requireId(request), id, dto));
    }
}
