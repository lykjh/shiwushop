package com.shiwushop.unit.product;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.service.impl.AdminProductStatusServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AdminProductStatusServiceImplTest {
    private final ProductMapper products = mock(ProductMapper.class);

    private final ShopService shops = mock(ShopService.class);
    private final AdminProductStatusServiceImpl service = new AdminProductStatusServiceImpl(products, shops);

    @Test
    void pendingReviewCannotBeTakenDownOutsideItsDecisionFlow() {
        Product product = new Product();
        product.setId(7L);
        product.setShopId(9L);
        product.setStatus(ProductStatus.PENDING_REVIEW);
        when(products.selectById(7L)).thenReturn(product);
        when(products.selectForUpdate(7L)).thenReturn(product);

        ProductStatusUpdateDTO request = new ProductStatusUpdateDTO();
        request.setStatus("OFF_SALE");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.adminTakeDown(7L, request)).getCode());
        verify(shops).lockById(9L);
        verify(products, never()).update(any(), any());
    }
}
