package com.shiwushop.product.controller.inventory;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.product.dto.inventory.InventoryMovementQuery;
import com.shiwushop.product.service.InventoryMovementService;
import com.shiwushop.product.vo.inventory.InventoryMovementPageVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InventoryMovementController {
    private final InventoryMovementService movements;

    /** 按 ID 游标读取当前店主的 SKU 库存流水。 */
    @GetMapping("/merchant/shops/{shopId}/skus/{skuId}/inventory-movements")
    public Result<InventoryMovementPageVO> list(@PathVariable @Positive Long shopId,
            @PathVariable @Positive Long skuId, @Valid @ModelAttribute InventoryMovementQuery query,
            HttpServletRequest request) {
        return Result.success(movements.list(SessionUser.requireId(request), shopId, skuId, query));
    }
}
