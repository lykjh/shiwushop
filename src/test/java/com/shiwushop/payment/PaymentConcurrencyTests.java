package com.shiwushop.payment;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.commerce.payment.AlipayGateway;
import com.shiwushop.commerce.payment.SdkAlipayGateway;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.commerce.entity.status.PaymentAttemptStatus;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.commerce.entity.order.OrderItem;
import com.shiwushop.commerce.entity.payment.PaymentAttempt;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.account.entity.user.UserProfile;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.account.service.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.commerce.mapper.order.OrderItemMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.commerce.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.commerce.service.PaymentService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class PaymentConcurrencyTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private PaymentService paymentService;
    @Autowired private UserService userService;
    @Autowired private PrivacyCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderItemMapper itemMapper;
    @Autowired private PaymentAttemptMapper paymentMapper;
    @Autowired private Clock businessClock;
    @MockitoBean private SdkAlipayGateway gateway;

    @Test
    void concurrentStartAndConfirmationCreateOneAttemptAndOnePaidOrder() throws Exception {
        String username = "payment-race-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        UserProfile buyer = new UserProfile();
        buyer.setId(jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username));
        Shop shop = null;
        Category category = null;
        Product product = null;
        Sku sku = null;
        Order order = null;
        Order overdue = null;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            shop = new Shop();
            shop.setOwnerUserId(buyer.getId());
            shop.setName("并发支付店铺-" + UUID.randomUUID());
            shop.setEnabled(1);
            shopMapper.insert(shop);
            category = new Category();
            category.setName("并发支付分类-" + UUID.randomUUID());
            category.setLevel(1);
            category.setVisible(1);
            category.setSortOrder(0);
            categoryMapper.insert(category);
            product = new Product();
            product.setShopId(shop.getId());
            product.setCategoryId(category.getId());
            product.setName("并发支付商品");
            product.setStatus(ProductStatus.ON_SALE);
            productMapper.insert(product);
            sku = new Sku();
            sku.setProductId(product.getId());
            String signature = UUID.randomUUID().toString().replace("-", "");
            sku.setOptionSignature(signature + signature);
            sku.setPrice(new BigDecimal("10.00"));
            sku.setStock(1);
            skuMapper.insert(sku);
            order = new Order();
            order.setUserId(buyer.getId());
            order.setShopId(shop.getId());
            order.setTotalAmount(new BigDecimal("20.00"));
            addressSnapshot(order, buyer.getId());
            order.setStatus(OrderStatus.UNPAID);
            order.setPayExpiresAt(LocalDateTime.now(businessClock).plusMinutes(15));
            orderMapper.insert(order);
            orderMapper.insertCheckoutItem(order.getId(), sku.getId(), product.getName(), "标准版",
                    2, sku.getPrice());

            when(gateway.configured()).thenReturn(true);
            when(gateway.appId()).thenReturn("sandbox-app");
            when(gateway.sellerId()).thenReturn("sandbox-seller");
            when(gateway.channel()).thenReturn("ALIPAY_SANDBOX");
            CountDownLatch start = new CountDownLatch(1);
            Long orderId = order.getId();
            Future<Long> first = workers.submit(() -> {
                start.await();
                return paymentService.start(buyer.getId(), orderId).paymentId();
            });
            Future<Long> second = workers.submit(() -> {
                start.await();
                return paymentService.start(buyer.getId(), orderId).paymentId();
            });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1L, paymentMapper.selectCount(new LambdaQueryWrapper<PaymentAttempt>()
                    .eq(PaymentAttempt::getOrderId, orderId)));

            PaymentAttempt attempt = paymentMapper.selectLatest(orderId);
            when(gateway.verify(anyMap())).thenReturn(true);
            when(gateway.query(attempt.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                    "TRADE_SUCCESS", attempt.getOutTradeNo(), "concurrent-trade-" + orderId,
                    new BigDecimal("20.00")));
            Map<String, String> form = Map.of("out_trade_no", attempt.getOutTradeNo(),
                    "trade_no", "concurrent-trade-" + orderId, "trade_status", "TRADE_SUCCESS",
                    "total_amount", "20.00", "app_id", "sandbox-app", "seller_id", "sandbox-seller");
            CountDownLatch confirm = new CountDownLatch(1);
            Future<?> query = workers.submit(() -> {
                confirm.await();
                paymentService.sync(buyer.getId(), orderId);
                return null;
            });
            Future<?> callback = workers.submit(() -> {
                confirm.await();
                paymentService.notifyPaid(form);
                return null;
            });
            confirm.countDown();
            query.get(10, TimeUnit.SECONDS);
            callback.get(10, TimeUnit.SECONDS);
            assertEquals(OrderStatus.PAID, orderMapper.selectById(orderId).getStatus());
            assertEquals(PaymentAttemptStatus.SUCCESS, paymentMapper.selectById(attempt.getId()).getStatus());
            assertEquals(2L, productMapper.getSoldCount(product.getId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                    Integer.class, product.getId()));

            // 到期扫描和支付宝通知同时确认另一张订单，不能取消并返库存。
            overdue = new Order();
            overdue.setUserId(buyer.getId());
            overdue.setShopId(shop.getId());
            overdue.setTotalAmount(new BigDecimal("10.00"));
            addressSnapshot(overdue, buyer.getId());
            overdue.setStatus(OrderStatus.UNPAID);
            overdue.setPayExpiresAt(LocalDateTime.now(businessClock).minusSeconds(1));
            orderMapper.insert(overdue);
            orderMapper.insertCheckoutItem(overdue.getId(), sku.getId(), product.getName(), "标准版",
                    1, sku.getPrice());
            sku.setStock(0);
            skuMapper.updateById(sku);
            PaymentAttempt pending = new PaymentAttempt();
            pending.setOrderId(overdue.getId());
            pending.setChannel("ALIPAY_SANDBOX");
            pending.setOutTradeNo("expired-" + UUID.randomUUID());
            pending.setAppId("sandbox-app");
            pending.setSellerId("sandbox-seller");
            pending.setAmount(new BigDecimal("10.00"));
            pending.setStatus(PaymentAttemptStatus.PENDING);
            paymentMapper.insert(pending);
            String tradeNo = "expired-trade-" + overdue.getId();
            when(gateway.query(pending.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                    "TRADE_SUCCESS", pending.getOutTradeNo(), tradeNo, new BigDecimal("10.00")));
            Map<String, String> lateNotice = Map.of("out_trade_no", pending.getOutTradeNo(),
                    "trade_no", tradeNo, "trade_status", "TRADE_SUCCESS", "total_amount", "10.00",
                    "app_id", "sandbox-app", "seller_id", "sandbox-seller");
            Long overdueId = overdue.getId();
            CountDownLatch expiryRace = new CountDownLatch(1);
            Future<?> expiry = workers.submit(() -> {
                expiryRace.await();
                paymentService.expireOrder(overdueId);
                return null;
            });
            Future<?> lateCallback = workers.submit(() -> {
                expiryRace.await();
                paymentService.notifyPaid(lateNotice);
                return null;
            });
            expiryRace.countDown();
            expiry.get(10, TimeUnit.SECONDS);
            lateCallback.get(10, TimeUnit.SECONDS);
            assertEquals(OrderStatus.PAID, orderMapper.selectById(overdueId).getStatus());
            assertEquals(0, skuMapper.selectById(sku.getId()).getStock());
            assertEquals(3L, productMapper.getSoldCount(product.getId()));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                    Integer.class, product.getId()));
        } finally {
            workers.shutdownNow();
            if (product != null && product.getId() != null) jdbc.update(
                    "DELETE FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?", product.getId());
            if (order != null && order.getId() != null) jdbc.update("DELETE FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?)", order.getId());
            if (overdue != null && overdue.getId() != null) jdbc.update("DELETE FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?)", overdue.getId());
            if (overdue != null && overdue.getId() != null) {
                paymentMapper.delete(new LambdaQueryWrapper<PaymentAttempt>()
                        .eq(PaymentAttempt::getOrderId, overdue.getId()));
                itemMapper.delete(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, overdue.getId()));
                orderMapper.deleteById(overdue.getId());
            }
            if (order != null && order.getId() != null) {
                paymentMapper.delete(new LambdaQueryWrapper<PaymentAttempt>()
                        .eq(PaymentAttempt::getOrderId, order.getId()));
                itemMapper.delete(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, order.getId()));
                orderMapper.deleteById(order.getId());
            }
            if (sku != null && sku.getId() != null) skuMapper.deleteById(sku.getId());
            if (product != null && product.getId() != null) {
                jdbc.update("DELETE FROM product_sales_stats WHERE product_id=?", product.getId());
                productMapper.deleteById(product.getId());
            }
            if (category != null && category.getId() != null) categoryMapper.deleteById(category.getId());
            if (shop != null && shop.getId() != null) shopMapper.deleteById(shop.getId());
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", buyer.getId());
            jdbc.update("DELETE FROM user_private WHERE user_id=?", buyer.getId());
            jdbc.update("DELETE FROM users WHERE id=?", buyer.getId());
        }
    }

    private void addressSnapshot(Order order, Long buyerId) {
        order.setConsigneeCiphertext(cipher.encrypt(buyerId, "orders:consignee", "张三"));
        order.setPhoneCiphertext(cipher.encrypt(buyerId, "orders:phone", "13800138000"));
        order.setProvince("上海市");
        order.setCity("上海市");
        order.setDistrict("浦东新区");
        order.setDetailCiphertext(cipher.encrypt(buyerId, "orders:detail", "测试地址"));
        order.setShippingKeyVersion(1);
    }
}
