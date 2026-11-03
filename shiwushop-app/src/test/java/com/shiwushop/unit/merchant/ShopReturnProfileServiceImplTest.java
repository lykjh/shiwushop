package com.shiwushop.unit.merchant;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.merchant.dto.shop.ShopReturnProfileSaveDTO;
import com.shiwushop.merchant.entity.shop.ShopReturnProfile;
import com.shiwushop.merchant.mapper.shop.ShopReturnProfileMapper;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.impl.ShopReturnProfileServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopReturnProfileServiceImplTest {
    private final MerchantShopAccess access = mock(MerchantShopAccess.class);
    private final ShopReturnProfileMapper mapper = mock(ShopReturnProfileMapper.class);
    private final PrivacyCipher cipher = new PrivacyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final ShopReturnProfileServiceImpl service = new ShopReturnProfileServiceImpl(access, mapper, cipher);

    @Test
    void saveLocksShopBeforeConfigurationAndEncrypts() {
        var dto = new ShopReturnProfileSaveDTO();
        dto.setContactName("王明");
        dto.setContactPhone("13912345678");
        dto.setConsignee("张明");
        dto.setPhone("13812345678");
        dto.setProvince("江苏省");
        dto.setCity("南京市");
        dto.setDistrict("鼓楼区");
        dto.setDetail("中山北路28号");
        assertEquals("张明", service.save(7L, 2L, dto).consignee());
        var order = inOrder(access, mapper);
        order.verify(access).requireOwnedForUpdate(2L, 7L, "店铺不存在");
        order.verify(mapper).selectForUpdate(7L);
        var saved = ArgumentCaptor.forClass(ShopReturnProfile.class);
        order.verify(mapper).insert(saved.capture());
        assertEquals("张明", cipher.decrypt(7L, "shop-return:consignee", 1, saved.getValue().getConsigneeCiphertext()));
        assertThrows(BusinessException.class, () -> cipher.decrypt(8L, "shop-return:consignee", 1, saved.getValue().getConsigneeCiphertext()));
        when(mapper.selectForUpdate(7L)).thenReturn(saved.getValue());
        var snapshot = service.snapshotForReturn(7L);
        assertEquals("张明", service.decryptReturnDestination(snapshot).consignee());
        saved.getValue().setConsigneeCiphertext(cipher.encrypt(7L, "shop-return:consignee", "李华"));
        assertEquals("张明", service.decryptReturnDestination(snapshot).consignee());
    }

    @Test
    void missingConfigurationConflictsAfterReadAuthorization() {
        assertThrows(BusinessException.class, () -> service.get(7L, 2L));
        verify(access).requireOwned(2L, 7L, "店铺不存在");
        verify(access, never()).requireOwnedForUpdate(anyLong(), anyLong(), anyString());
        assertThrows(BusinessException.class, () -> service.snapshotForReturn(7L));
    }
    @Test
    void encryptionFailureLeavesStoredValueUntouched() {
        var brokenCipher = new PrivacyCipher("");
        var failingService = new ShopReturnProfileServiceImpl(access, mapper, brokenCipher);
        var dto = new ShopReturnProfileSaveDTO();
        dto.setContactName("王明");
        when(mapper.selectForUpdate(7L)).thenReturn(new ShopReturnProfile());
        assertThrows(BusinessException.class, () -> failingService.save(7L, 2L, dto));
        verify(mapper, never()).insert(any(ShopReturnProfile.class));
        verify(mapper, never()).updateById(any(ShopReturnProfile.class));
    }
}
