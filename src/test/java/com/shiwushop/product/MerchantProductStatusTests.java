package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductImage;
import com.shiwushop.catalog.entity.product.ProductOption;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductImageMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.product.SkuOptionValueMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;

import com.shiwushop.catalog.service.ProductService;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.common.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.account.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantProductStatusTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private ProductService productService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private ProductImageMapper imageMapper;
    @Autowired private ProductOptionMapper optionMapper;
    @Autowired private ProductOptionValueMapper valueMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private SkuOptionValueMapper linkMapper;

    @Test
    void rejectsNullProductIdsBeforeQueries() {
        BusinessException publicError = assertThrows(BusinessException.class,
                () -> productService.publicDetail(null));
        assertEquals(400, publicError.getCode());
        assertEquals("商品ID必须大于0", publicError.getMessage());

        BusinessException merchantError = assertThrows(BusinessException.class,
                () -> productService.updateMerchantProductStatus(1L, null, null));
        assertEquals(400, merchantError.getCode());
        assertEquals("商品ID必须大于0", merchantError.getMessage());

        BusinessException skuError = assertThrows(BusinessException.class,
                () -> productService.updateMerchantSku(1L, null, 1L, null));
        assertEquals(400, skuError.getCode());
        assertEquals("ID必须大于0", skuError.getMessage());
    }

    @Test
    void publishesAndTakesDownCompleteProductEvenWhenSkuStockIsZero() throws Exception {
        Fixture f = fixture();
        MockHttpSession session = session(f.owner);

        mockMvc.perform(request(f.product.getId(), "ON_SALE", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(f.product.getId()))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));
        assertEquals(ProductStatus.ON_SALE, productMapper.selectById(f.product.getId()).getStatus());

        mockMvc.perform(request(f.product.getId(), "OFF_SALE", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"));
        assertEquals(ProductStatus.OFF_SALE, productMapper.selectById(f.product.getId()).getStatus());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, f.product.getId()));
    }

    @Test
    void rejectsIncompleteProductAndPausedShopBeforePublishing() throws Exception {
        Fixture f = fixture();
        MockHttpSession session = session(f.owner);

        imageMapper.update(null, new LambdaUpdateWrapper<ProductImage>()
                .eq(ProductImage::getProductId, f.product.getId()).set(ProductImage::getIsCover, 0));
        mockMvc.perform(request(f.product.getId(), "ON_SALE", session))
                .andExpect(status().isConflict());

        imageMapper.update(null, new LambdaUpdateWrapper<ProductImage>()
                .eq(ProductImage::getProductId, f.product.getId()).set(ProductImage::getIsCover, 1));
        imageMapper.delete(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, f.product.getId()));
        mockMvc.perform(request(f.product.getId(), "ON_SALE", session))
                .andExpect(status().isConflict());

        image(f.product, f.owner);
        f.shop.setEnabled(0);
        shopMapper.updateById(f.shop);
        mockMvc.perform(request(f.product.getId(), "ON_SALE", session))
                .andExpect(status().isConflict());
        assertEquals(ProductStatus.DRAFT, productMapper.selectById(f.product.getId()).getStatus());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, f.product.getId()));
    }

    @Test
    void rejectsMissingSkuForeignOwnerAndInvalidStatus() throws Exception {
        Fixture f = fixture();
        Long outsider = user();
        MockHttpSession session = session(f.owner);

        mockMvc.perform(put("/merchant/products/{id}/status", f.product.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_SALE\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(request(f.product.getId(), "ON_SALE", session(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(request(f.product.getId(), "PUBLISHED", session))
                .andExpect(status().isBadRequest());

        ProductStatusUpdateDTO unsupported = new ProductStatusUpdateDTO();
        unsupported.setStatus("DRAFT");
        BusinessException error = assertThrows(BusinessException.class,
                () -> productService.updateMerchantProductStatus(f.owner, f.product.getId(), unsupported));
        assertEquals(400, error.getCode());
        assertEquals(ProductStatus.DRAFT, productMapper.selectById(f.product.getId()).getStatus());

        jdbcTemplate.update("DELETE FROM sku_option_values WHERE sku_id = ?", f.sku.getId());
        skuMapper.deleteById(f.sku.getId());
        mockMvc.perform(request(f.product.getId(), "ON_SALE", session))
                .andExpect(status().isConflict());
        assertEquals(ProductStatus.DRAFT, productMapper.selectById(f.product.getId()).getStatus());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            Long productId, String nextStatus, MockHttpSession session) {
        return put("/merchant/products/{id}/status", productId)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + nextStatus + "\"}");
    }

    private Fixture fixture() throws Exception {
        Long owner = user();
        Shop shop = new Shop();
        shop.setOwnerUserId(owner);
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);

        Category root = new Category();
        root.setName("一级-" + UUID.randomUUID());
        root.setLevel(1);
        root.setVisible(1);
        root.setSortOrder(0);
        categoryMapper.insert(root);
        Category child = new Category();
        child.setName("二级-" + UUID.randomUUID());
        child.setParentId(root.getId());
        child.setLevel(2);
        child.setVisible(1);
        child.setSortOrder(0);
        categoryMapper.insert(child);

        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(child.getId());
        product.setName("商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);
        image(product, owner);

        ProductOption option = new ProductOption();
        option.setProductId(product.getId());
        option.setName("颜色");
        option.setSortOrder(0);
        optionMapper.insert(option);
        ProductOptionValue value = new ProductOptionValue();
        value.setOptionId(option.getId());
        value.setValueName("白色");
        value.setSortOrder(0);
        valueMapper.insert(value);

        Sku sku = new Sku();
        sku.setProductId(product.getId());
        sku.setOptionSignature(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getId().toString().getBytes(StandardCharsets.UTF_8))));
        sku.setPrice(new BigDecimal("19.90"));
        sku.setStock(0);
        skuMapper.insert(sku);
        linkMapper.insertLink(sku.getId(), value.getId());
        return new Fixture(owner, shop, product, sku);
    }

    private void image(Product product, Long owner) {
        String key = "test/product-status-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO media_assets(uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status) " +
                "VALUES (?,?,'image/png',10,1,1,UNHEX(SHA2(?,256)),'READY')", owner, key, key);
        Long mediaId = jdbcTemplate.queryForObject("SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
        ProductImage image = new ProductImage();
        image.setProductId(product.getId());
        image.setMediaId(mediaId);
        image.setIsCover(1);
        image.setSortOrder(0);
        imageMapper.insert(image);
    }

    private Long user() {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = 'MERCHANT' WHERE username = ?", username);
        return jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private MockHttpSession session(Long user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }

    private record Fixture(Long owner, Shop shop, Product product, Sku sku) {}
}
