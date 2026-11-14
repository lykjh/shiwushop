package com.shiwushop.merchant.controller.shop;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.merchant.dto.application.MerchantApplicationSaveDTO;
import com.shiwushop.merchant.service.MerchantApplicationService;
import com.shiwushop.merchant.vo.application.MerchantApplicationVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/merchant/applications")
@RequiredArgsConstructor
public class MerchantApplicationController {
    private final MerchantApplicationService applications;

    /** 本人创建私有资质申请草稿。 */
    @PostMapping
    public Result<MerchantApplicationVO> create(@Valid @RequestBody MerchantApplicationSaveDTO body,
                                                HttpServletRequest request) {
        return Result.success(applications.create(SessionUser.requireId(request), body));
    }

    @PutMapping("/{id}")
    public Result<MerchantApplicationVO> update(@PathVariable Long id,
                                                @Valid @RequestBody MerchantApplicationSaveDTO body,
                                                HttpServletRequest request) {
        return Result.success(applications.update(SessionUser.requireId(request), id, body));
    }

    @PostMapping("/{id}/submit")
    public Result<MerchantApplicationVO> submit(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(applications.submit(SessionUser.requireId(request), id));
    }

    /** 撤回获批资质时同步停业；续证需重新提交审核。 */
    @PostMapping("/{id}/withdraw")
    public Result<MerchantApplicationVO> withdraw(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(applications.withdraw(SessionUser.requireId(request), id));
    }

    @GetMapping("/{id}")
    public Result<MerchantApplicationVO> mine(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(applications.mine(SessionUser.requireId(request), id));
    }
}
