package com.shiwushop.merchant.controller.admin;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.common.media.service.MediaReadService;
import com.shiwushop.merchant.dto.application.MerchantApplicationDecisionDTO;
import com.shiwushop.merchant.service.MerchantApplicationService;
import com.shiwushop.merchant.vo.application.MerchantApplicationVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/merchant-applications")
@RequiredArgsConstructor
public class AdminMerchantApplicationController {
    private final MerchantApplicationService applications;
    private final MediaReadService media;
    private final AdminAuth adminAuth;

    @GetMapping
    public Result<List<MerchantApplicationVO>> pending(HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(applications.pending());
    }

    /** 审核使用提交版本，拒绝重复或过期决定。 */
    @PostMapping("/{id}/decision")
    public Result<MerchantApplicationVO> decide(@PathVariable Long id,
                                                @Valid @RequestBody MerchantApplicationDecisionDTO body,
                                                HttpServletRequest request) {
        return Result.success(applications.decide(adminAuth.requireAdmin(request), id, body));
    }

    /** 先验证管理员及申请关联，再按不可缓存的私有媒体方式读取证照。 */
    @GetMapping("/{id}/documents/{documentType}")
    public ResponseEntity<Resource> document(@PathVariable Long id, @PathVariable String documentType,
                                             HttpServletRequest request) {
        Long reviewerId = adminAuth.requireAdmin(request);
        Long mediaId = applications.reviewMediaId(id, documentType);
        MediaReadService.ImageFile image = media.readCredentialForReview(mediaId, reviewerId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mimeType())).body(image.resource());
    }
}
