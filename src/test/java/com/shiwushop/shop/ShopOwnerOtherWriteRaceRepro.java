package com.shiwushop.shop;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.CaptchaProofStore;
import com.shiwushop.common.auth.MerchantShopAccess;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.dto.product.MerchantProductCreateDTO;
import com.shiwushop.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.entity.category.Category;
import com.shiwushop.entity.order.Order;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.entity.status.OrderStatus;
import com.shiwushop.mapper.category.CategoryMapper;
import com.shiwushop.mapper.order.OrderMapper;
import com.shiwushop.mapper.shop.ShopMapper;
import com.shiwushop.service.AfterSaleService;
import com.shiwushop.service.ProductService;
import com.shiwushop.service.ShipmentService;
import com.shiwushop.service.ShopService;
import com.shiwushop.service.UserService;
import com.shiwushop.test.TestRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

// 显式运行的缺陷复现；修复前不进入普通 Maven 测试扫描。
@SpringBootTest
class ShopOwnerOtherWriteRaceRepro {
    @Autowired private CaptchaProofStore proofs;
    @Autowired private UserService users;
    @Autowired private ShopService shops;
    @Autowired private ProductService products;
    @Autowired private ShipmentService shipments;
    @Autowired private AfterSaleService afterSales;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivacyCipher cipher;
    @MockitoSpyBean private MerchantShopAccess merchantAccess;

    @Test
    void transferredShopRejectsThreeInFlightMerchantWrites() throws Exception {
        Long oldOwner = user("MERCHANT");
        Long newOwner = user("USER");
        Long buyer = user("USER");
        Shop shop = new Shop();
        shop.setName("转让写入竞态-" + UUID.randomUUID());
        shop.setOwnerUserId(oldOwner);
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Category root = category(null, 1);
        Category child = category(root.getId(), 2);
        Order shipmentOrder = order(shop.getId(), buyer);
        Order afterSaleOrder = order(shop.getId(), buyer);
        jdbc.update("INSERT INTO after_sale_cases (order_id,type,status,reason,refund_amount,requested_at) "
                + "VALUES (?,'REFUND_ONLY','REQUESTED','测试',10,NOW())", afterSaleOrder.getId());
        Long caseId = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class,
                afterSaleOrder.getId());

        CountDownLatch checkedOwner = new CountDownLatch(3);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(call -> {
            Shop checked = (Shop) call.callRealMethod();
            checkedOwner.countDown();
            assertTrue(proceed.await(10, TimeUnit.SECONDS));
            return checked;
        }).when(merchantAccess).requireOwned(eq(oldOwner), eq(shop.getId()), anyString());

        try (var workers = Executors.newFixedThreadPool(3)) {
            MerchantProductCreateDTO draft = new MerchantProductCreateDTO();
            draft.setShopId(shop.getId());
            draft.setCategoryId(child.getId());
            draft.setName("转让后创建-" + UUID.randomUUID());
            Future<?> product = workers.submit(() -> products.createDraft(oldOwner, draft));
            Future<?> shipment = workers.submit(() -> shipments.ship(oldOwner, shop.getId(),
                    shipmentOrder.getId(), new ShipmentCreateDTO("顺丰", "RACE-1")));
            Future<?> review = workers.submit(() -> afterSales.review(oldOwner, shop.getId(),
                    caseId, new AfterSaleReviewDTO(true, null)));
            try {
                assertTrue(checkedOwner.await(10, TimeUnit.SECONDS), "三个请求都应停在归属校验后");
                shops.changeOwner(shop.getId(), newOwner);
            } finally {
                proceed.countDown();
            }
            String productResult = outcome(product);
            String shipmentResult = outcome(shipment);
            String reviewResult = outcome(review);
            int createdProducts = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE shop_id=?",
                    Integer.class, shop.getId());
            int createdShipments = jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?",
                    Integer.class, shipmentOrder.getId());
            String saleStatus = jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?",
                    String.class, caseId);
            System.out.printf("转让后旧店主写入：商品=%s/%d，发货=%s/%d，售后审核=%s/%s%n",
                    productResult, createdProducts, shipmentResult, createdShipments, reviewResult, saleStatus);
            assertAll(
                    () -> assertEquals("REJECTED_404", productResult, "商品创建"),
                    () -> assertEquals("REJECTED_404", shipmentResult, "商家发货"),
                    () -> assertEquals("REJECTED_404", reviewResult, "售后审核"),
                    () -> assertEquals(0, createdProducts, "转让后新增商品行数"),
                    () -> assertEquals(0, createdShipments, "转让后新增发货行数"),
                    () -> assertEquals("REQUESTED", saleStatus, "转让后售后状态"));
        } finally {
            proceed.countDown();
            jdbc.update("DELETE FROM audit_events WHERE actor_user_id IN (?,?,?)", oldOwner, newOwner, buyer);
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='PRODUCT' AND aggregate_id IN "
                    + "(SELECT id FROM products WHERE shop_id=?)", shop.getId());
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id IN "
                    + "(SELECT id FROM shipments WHERE order_id=?)", shipmentOrder.getId());
            jdbc.update("DELETE FROM shipments WHERE order_id=?", shipmentOrder.getId());
            jdbc.update("DELETE FROM after_sale_cases WHERE id=?", caseId);
            jdbc.update("DELETE FROM products WHERE shop_id=?", shop.getId());
            jdbc.update("DELETE FROM orders WHERE shop_id=?", shop.getId());
            jdbc.update("DELETE FROM categories WHERE id=?", child.getId());
            jdbc.update("DELETE FROM categories WHERE id=?", root.getId());
            jdbc.update("DELETE FROM shops WHERE id=?", shop.getId());
            jdbc.update("DELETE FROM user_accounts WHERE user_id IN (?,?,?)", oldOwner, newOwner, buyer);
            jdbc.update("DELETE FROM users WHERE id IN (?,?,?)", oldOwner, newOwner, buyer);
        }
    }

    private String outcome(Future<?> action) throws Exception {
        try {
            action.get(10, TimeUnit.SECONDS);
            return "WROTE";
        } catch (ExecutionException error) {
            if (error.getCause() instanceof BusinessException business) {
                return "REJECTED_" + business.getCode();
            }
            throw error;
        }
    }

    private Long user(String role) {
        String name = "owner_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(name, "Shop123!", null));
        jdbc.update("UPDATE user_accounts SET role=? WHERE username=?", role, name);
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, name);
    }

    private Category category(Long parentId, int level) {
        Category category = new Category();
        category.setName("转让分类-" + UUID.randomUUID());
        category.setParentId(parentId);
        category.setLevel(level);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }

    private Order order(Long shopId, Long buyer) {
        Order order = new Order();
        order.setShopId(shopId);
        order.setUserId(buyer);
        order.setTotalAmount(new BigDecimal("40.00"));
        order.setStatus(OrderStatus.PAID);
        order.setPayExpiresAt(LocalDateTime.now().plusHours(1));
        order.setPaidAt(LocalDateTime.now());
        order.setConsigneeCiphertext(cipher.encrypt(buyer, "orders:consignee", "张三"));
        order.setPhoneCiphertext(cipher.encrypt(buyer, "orders:phone", "13800138000"));
        order.setDetailCiphertext(cipher.encrypt(buyer, "orders:detail", "下单地址"));
        order.setProvince("北京");
        order.setCity("北京");
        order.setDistrict("海淀");
        order.setShippingKeyVersion(1);
        orderMapper.insert(order);
        return order;
    }
}
