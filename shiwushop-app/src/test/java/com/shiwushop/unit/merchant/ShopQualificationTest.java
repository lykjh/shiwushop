package com.shiwushop.unit.merchant;

import com.shiwushop.merchant.entity.shop.Shop;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class ShopQualificationTest {
    @Test
    void expiredOrWithdrawnQualificationBlocksTradingButLegacyShopKeepsItsStatus() {
        Shop shop = new Shop();
        shop.setEnabled(1);
        assertTrue(shop.isTradingEnabled());

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        shop.setQualificationValidUntil(today);
        assertTrue(shop.isTradingEnabled());
        shop.setQualificationValidUntil(today.minusDays(1));
        assertFalse(shop.isTradingEnabled());

        shop.setQualificationValidUntil(today.plusDays(1));
        shop.setQualificationRevoked(1);
        assertFalse(shop.isTradingEnabled());
    }
}
