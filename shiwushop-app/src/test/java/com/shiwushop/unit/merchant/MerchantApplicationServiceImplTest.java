package com.shiwushop.unit.merchant;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.merchant.entity.application.MerchantApplication;
import com.shiwushop.merchant.entity.application.MerchantApplicationReview;
import com.shiwushop.merchant.dto.application.MerchantApplicationDecisionDTO;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.mapper.application.MerchantApplicationDocumentMapper;
import com.shiwushop.merchant.mapper.application.MerchantApplicationMapper;
import com.shiwushop.merchant.mapper.application.MerchantApplicationReviewMapper;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.service.impl.MerchantApplicationServiceImpl;
import com.shiwushop.user.service.AccountAccess;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantApplicationServiceImplTest {
    @BeforeAll
    static void registerShopMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Shop.class);
    }

    private final MerchantApplicationMapper applications = mock(MerchantApplicationMapper.class);
    private final MerchantApplicationDocumentMapper documents = mock(MerchantApplicationDocumentMapper.class);
    private final MerchantApplicationReviewMapper reviews = mock(MerchantApplicationReviewMapper.class);
    private final ShopMapper shops = mock(ShopMapper.class);

    private final MerchantApplicationServiceImpl service = new MerchantApplicationServiceImpl(
            applications, documents, reviews, shops,
            mock(AccountAccess.class), mock(MediaAssetAccess.class));

    @Test
    void withdrawalStopsApprovedShopAndCannotInterruptPendingReview() {
        MerchantApplication application = new MerchantApplication();
        application.setId(3L);
        application.setApplicantUserId(4L);
        application.setShopId(9L);
        application.setVersion(1L);
        application.setStatus("APPROVED");
        Shop shop = new Shop();
        shop.setId(9L);
        shop.setOwnerUserId(4L);
        shop.setEnabled(1);
        when(applications.selectById(3L)).thenReturn(application);
        when(applications.selectForUpdate(3L)).thenReturn(application);
        when(shops.selectForUpdate(9L)).thenReturn(shop);
        when(shops.selectById(9L)).thenReturn(shop);
        when(documents.selectList(any())).thenReturn(List.of());

        assertEquals("WITHDRAWN", service.withdraw(4L, 3L).status());
        verify(shops).update(any(), any());
        assertEquals("WITHDRAWN", service.withdraw(4L, 3L).status());

        application.setStatus("SUBMITTED");
        assertEquals(409, assertThrows(BusinessException.class, () -> service.withdraw(4L, 3L)).getCode());
    }

    @Test
    void reviewRejectsOldVersionAndReplaysOnlyTheSameDecision() {
        MerchantApplication application = new MerchantApplication();
        application.setId(3L);
        application.setVersion(2L);
        application.setStatus("APPROVED");
        when(applications.selectById(3L)).thenReturn(application);
        when(applications.selectForUpdate(3L)).thenReturn(application);
        when(documents.selectList(any())).thenReturn(List.of());
        MerchantApplicationReview prior = new MerchantApplicationReview();
        prior.setDecision("APPROVED");
        when(reviews.selectOne(any())).thenReturn(prior);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.decide(8L, 3L, new MerchantApplicationDecisionDTO(1L, true, null))).getCode());
        assertEquals("APPROVED", service.decide(8L, 3L,
                new MerchantApplicationDecisionDTO(2L, true, null)).status());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.decide(8L, 3L, new MerchantApplicationDecisionDTO(2L, false, "不通过"))).getCode());
        verify(reviews, never()).insert(any(MerchantApplicationReview.class));
        verify(shops, never()).insert(any(Shop.class));
    }
}
