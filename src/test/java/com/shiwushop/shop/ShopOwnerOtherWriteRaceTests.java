package com.shiwushop.shop;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.catalog.auth.MerchantShopAccess;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.commerce.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnReceiptDTO;
import com.shiwushop.catalog.dto.product.MerchantProductCreateDTO;
import com.shiwushop.catalog.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductOptionSaveDTO;
import com.shiwushop.catalog.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.catalog.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.commerce.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.catalog.entity.status.ProductStatus;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.commerce.service.AfterSaleService;
import com.shiwushop.catalog.service.ProductService;
import com.shiwushop.commerce.service.ShipmentService;
import com.shiwushop.catalog.service.ShopService;
import com.shiwushop.account.service.RegisterService;
import com.shiwushop.test.TestRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
class ShopOwnerOtherWriteRaceTests {
    @Autowired private CaptchaProofStore proofs;
    @Autowired private RegisterService users;
    @Autowired private ShopService shops;
    @Autowired private ProductService products;
    @Autowired private ShipmentService shipments;
    @Autowired private AfterSaleService afterSales;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivacyCipher cipher;
    @MockitoSpyBean private MerchantShopAccess merchantAccess;

    @Test
    void transferredShopRejectsAllInFlightMerchantWrites() throws Exception {
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
        Product existing = new Product();
        existing.setShopId(shop.getId());
        existing.setCategoryId(child.getId());
        existing.setName("转让前商品-" + UUID.randomUUID());
        existing.setStatus(ProductStatus.DRAFT);
        productMapper.insert(existing);
        Order shipmentOrder = order(shop.getId(), buyer);
        Order afterSaleOrder = order(shop.getId(), buyer);
        jdbc.update("INSERT INTO after_sale_cases (order_id,type,status,reason,refund_amount,requested_at) "
                + "VALUES (?,'REFUND_ONLY','REQUESTED','测试',10,NOW())", afterSaleOrder.getId());
        Long caseId = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class,
                afterSaleOrder.getId());

        CountDownLatch checkedOwner = new CountDownLatch(10);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(call -> {
            checkedOwner.countDown();
            assertTrue(proceed.await(10, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(merchantAccess).requireOwnedForUpdate(eq(oldOwner), eq(shop.getId()), anyString());

        try (var workers = Executors.newFixedThreadPool(10)) {
            MerchantProductCreateDTO draft = new MerchantProductCreateDTO();
            draft.setShopId(shop.getId());
            draft.setCategoryId(child.getId());
            draft.setName("转让后创建-" + UUID.randomUUID());
            MerchantProductUpdateDTO update = new MerchantProductUpdateDTO();
            update.setCategoryId(child.getId());
            update.setName("转让后修改");
            ProductOptionSaveDTO option = new ProductOptionSaveDTO();
            option.setName("颜色");
            option.setValues(List.of("红色"));
            ProductOptionsUpdateDTO options = new ProductOptionsUpdateDTO();
            options.setOptions(List.of(option));
            MerchantSkuCreateDTO sku = new MerchantSkuCreateDTO();
            sku.setOptionValueIds(List.of(1L));
            sku.setPrice(new BigDecimal("10.00"));
            sku.setStock(1);
            ProductStatusUpdateDTO status = new ProductStatusUpdateDTO();
            status.setStatus("OFF_SALE");
            Map<String, Callable<?>> actions = new LinkedHashMap<>();
            actions.put("createDraft", () -> products.createDraft(oldOwner, draft));
            actions.put("updateProduct", () -> products.updateMerchantProduct(oldOwner, existing.getId(), update));
            actions.put("replaceOptions", () -> products.replaceOptions(oldOwner, existing.getId(), options));
            actions.put("createSku", () -> products.createMerchantSku(oldOwner, existing.getId(), sku));
            actions.put("updateStatus", () -> products.updateMerchantProductStatus(oldOwner, existing.getId(), status));
            actions.put("ship", () -> shipments.ship(oldOwner, shop.getId(), shipmentOrder.getId(),
                    new ShipmentCreateDTO("顺丰", "RACE-1")));
            actions.put("review", () -> afterSales.review(oldOwner, shop.getId(), caseId,
                    new AfterSaleReviewDTO(true, null)));
            actions.put("receiveReturn", () -> afterSales.receiveReturn(oldOwner, shop.getId(), caseId,
                    new ReturnReceiptDTO(false)));
            actions.put("refund", () -> afterSales.refund(oldOwner, shop.getId(), caseId));
            actions.put("syncRefund", () -> afterSales.syncRefund(oldOwner, shop.getId(), caseId));
            Map<String, Future<?>> pending = new LinkedHashMap<>();
            actions.forEach((name, action) -> pending.put(name, workers.submit(action)));
            try {
                assertTrue(checkedOwner.await(10, TimeUnit.SECONDS), "十个请求都应停在店铺行锁前");
                shops.changeOwner(shop.getId(), newOwner);
            } finally {
                proceed.countDown();
            }
            Map<String, String> results = new LinkedHashMap<>();
            pending.forEach((name, future) -> {
                try {
                    results.put(name, outcome(future));
                } catch (Exception error) {
                    throw new AssertionError(name, error);
                }
            });
            int createdProducts = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE shop_id=?",
                    Integer.class, shop.getId());
            int createdShipments = jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?",
                    Integer.class, shipmentOrder.getId());
            String saleStatus = jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?",
                    String.class, caseId);
            System.out.printf("转让后旧店主写入：%s；商品=%d，发货=%d，售后=%s%n",
                    results, createdProducts, createdShipments, saleStatus);
            assertAll(
                    results.entrySet().stream()
                            .map(entry -> (org.junit.jupiter.api.function.Executable) () ->
                                    assertEquals("REJECTED_404", entry.getValue(), entry.getKey())));
            assertAll(
                    () -> assertEquals(1, createdProducts, "转让后商品行数"),
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
            jdbc.update("DELETE FROM refund_attempts WHERE case_id=?", caseId);
            jdbc.update("DELETE FROM return_shipments WHERE case_id=?", caseId);
            jdbc.update("DELETE FROM after_sale_items WHERE case_id=?", caseId);
            jdbc.update("DELETE FROM sku_option_values WHERE sku_id IN (SELECT id FROM skus WHERE product_id IN "
                    + "(SELECT id FROM products WHERE shop_id=?))", shop.getId());
            jdbc.update("DELETE FROM skus WHERE product_id IN (SELECT id FROM products WHERE shop_id=?)", shop.getId());
            jdbc.update("DELETE FROM product_option_values WHERE option_id IN (SELECT id FROM product_options "
                    + "WHERE product_id IN (SELECT id FROM products WHERE shop_id=?))", shop.getId());
            jdbc.update("DELETE FROM product_options WHERE product_id IN (SELECT id FROM products WHERE shop_id=?)",
                    shop.getId());
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
