package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.catalog.entity.brand.Brand;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductImage;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductImageMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantProductCreateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private BrandMapper brandMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private ProductImageMapper productImageMapper;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void oldAdminProductCreationRoutesAreGone() throws Exception {
        mockMvc.perform(post("/products"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/products/1/skus"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCreatesDraftWithOrderedImages() throws Exception {
        Long owner = user("MERCHANT");
        Shop shop = shop(owner);
        Category root = category(null, 1, 1);
        Category child = category(root.getId(), 2, 1);
        Brand brand = brand(1);
        Long coverId = mediaId(owner, "READY");
        Long detailId = mediaId(owner, "READY");
        String name = "商品-" + UUID.randomUUID();

        mockMvc.perform(post("/merchant/products")
                        .session(session(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "shopId", shop.getId(),
                                "categoryId", child.getId(),
                                "brandId", brand.getId(),
                                "name", name,
                                "description", "测试介绍",
                                "coverMediaId", coverId,
                                "imageMediaIds", List.of(coverId, detailId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.shopId").value(shop.getId()))
                .andExpect(jsonPath("$.data.categoryId").value(child.getId()))
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        Product saved = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getName, name));
        assertNotNull(saved);
        assertEquals(ProductStatus.DRAFT, saved.getStatus());
        assertEquals("测试介绍", saved.getDescription());

        List<ProductImage> images = productImageMapper.selectList(
                new LambdaQueryWrapper<ProductImage>()
                        .eq(ProductImage::getProductId, saved.getId())
                        .orderByAsc(ProductImage::getSortOrder));
        assertEquals(2, images.size());
        assertEquals(coverId, images.get(0).getMediaId());
        assertEquals(1, images.get(0).getIsCover());
        assertEquals(0, images.get(0).getSortOrder());
        assertEquals(detailId, images.get(1).getMediaId());
        assertEquals(0, images.get(1).getIsCover());
        assertEquals(1, images.get(1).getSortOrder());
        assertEquals(brand.getId(), jdbcTemplate.queryForObject(
                "SELECT brand_id FROM product_brands WHERE product_id = ?", Long.class, saved.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, saved.getId()));
    }

    @Test
    void requiresMerchantAndShopOwnership() throws Exception {
        Long owner = user("MERCHANT");
        Shop shop = shop(owner);
        Category root = category(null, 1, 1);
        Category child = category(root.getId(), 2, 1);
        String body = body(shop.getId(), child.getId(), "待授权商品");

        mockMvc.perform(post("/merchant/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/merchant/products")
                        .session(session(user("MERCHANT")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        Long ordinaryOwner = user("USER");
        Shop ordinaryShop = shop(ordinaryOwner);
        mockMvc.perform(post("/merchant/products")
                        .session(session(ordinaryOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ordinaryShop.getId(), child.getId(), "普通用户商品")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsRootCategoryDisabledBrandAndInvalidFields() throws Exception {
        Long owner = user("MERCHANT");
        Shop shop = shop(owner);
        Category root = category(null, 1, 1);
        Category child = category(root.getId(), 2, 1);
        Brand disabled = brand(0);
        MockHttpSession session = session(owner);

        mockMvc.perform(post("/merchant/products")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(shop.getId(), root.getId(), "一级分类商品")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/merchant/products")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "shopId", shop.getId(), "categoryId", child.getId(),
                                "brandId", disabled.getId(), "name", "停用品牌商品"))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/merchant/products")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(shop.getId(), child.getId(), " ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/merchant/products")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "shopId", shop.getId(), "categoryId", child.getId(),
                                "name", "图片非法", "imageUrls", List.of("https://example.com/a.jpg")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/merchant/products")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "shopId", shop.getId(), "categoryId", child.getId(),
                                "name", "其他用户图片", "imageMediaIds", List.of(mediaId(user("MERCHANT"), "READY"))))))
                .andExpect(status().isBadRequest());
        Long duplicateMediaId = mediaId(owner, "READY");
        mockMvc.perform(post("/merchant/products")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "shopId", shop.getId(), "categoryId", child.getId(),
                                "name", "重复图片", "imageMediaIds", List.of(duplicateMediaId, duplicateMediaId)))))
                .andExpect(status().isBadRequest());
    }

    private String body(Long shopId, Long categoryId, String name) {
        return om.writeValueAsString(Map.of(
                "shopId", shopId, "categoryId", categoryId, "name", name));
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }

    private Long user(String role) {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private Shop shop(Long owner) {
        Shop shop = new Shop();
        shop.setOwnerUserId(owner);
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        return shop;
    }

    private Category category(Long parentId, int level, int visible) {
        Category category = new Category();
        category.setName("分类-" + UUID.randomUUID());
        category.setParentId(parentId);
        category.setLevel(level);
        category.setVisible(visible);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }

    private Brand brand(int enabled) {
        Brand brand = new Brand();
        brand.setName("品牌-" + UUID.randomUUID());
        brand.setEnabled(enabled);
        brandMapper.insert(brand);
        return brand;
    }

    private Long mediaId(Long ownerId, String status) {
        String key = "test/product-create-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), ?)
                """, ownerId, key, key, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }
}
