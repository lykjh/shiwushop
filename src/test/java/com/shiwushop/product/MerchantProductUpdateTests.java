package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

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

import com.shiwushop.entity.status.ProductStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantProductUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopMapper shops;
    @Autowired private CategoryMapper categories;
    @Autowired private BrandMapper brands;
    @Autowired private ProductMapper products;
    @Autowired private ProductImageMapper productImages;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void replacesBrandAndImagesOnDraft() throws Exception {
        Long owner = user("MERCHANT");
        Category child = child();
        Product product = product(owner, child, "DRAFT");
        Brand brand = brand();
        products.insertBrand(product.getId(), brand.getId());
        image(product, media(owner), true, 0);
        Long cover = media(owner), detail = media(owner);

        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("categoryId", child.getId(), "name", "新版商品",
                        "description", "新版介绍", "coverMediaId", cover,
                        "imageMediaIds", List.of(cover, detail)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandId").value(org.hamcrest.Matchers.nullValue()));
        assertNull(products.selectBrandId(product.getId()));
        assertEquals("新版商品", products.selectById(product.getId()).getName());
        assertEquals("新版介绍", products.selectById(product.getId()).getDescription());
        assertEquals(List.of(cover, detail), images(product).stream().map(ProductImage::getMediaId).toList());
        assertEquals(1, images(product).getFirst().getIsCover());

        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("categoryId", child.getId(), "name", "清空图片",
                        "brandId", brand.getId(), "imageMediaIds", List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.brandId").value(brand.getId()));
        assertEquals(brand.getId(), products.selectBrandId(product.getId()));
        assertTrue(images(product).isEmpty());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, product.getId()));
    }

    @Test
    void omittedImagesStayAndCoverCanChange() throws Exception {
        Long owner = user("MERCHANT");
        Category child = child();
        Product product = product(owner, child, "DRAFT");
        Long first = media(owner), second = media(owner);
        image(product, first, true, 0);
        image(product, second, false, 1);
        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("categoryId", child.getId(), "name", "只换封面",
                        "coverMediaId", second))))
                .andExpect(status().isOk());
        assertEquals(List.of(first, second), images(product).stream().map(ProductImage::getMediaId).toList());
        assertEquals(List.of(0, 1), images(product).stream().map(ProductImage::getIsCover).toList());
    }

    @Test
    void rejectsUnauthorizedAndInvalidMedia() throws Exception {
        Long owner = user("MERCHANT");
        Category child = child();
        Product product = product(owner, child, "DRAFT");
        String body = body(child.getId(), "测试");
        mvc.perform(put("/merchant/products/{id}", product.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(user("MERCHANT")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("categoryId", child.getId(), "name", "旧 URL",
                        "coverUrl", "https://example.com/a.jpg"))))
                .andExpect(status().isBadRequest());
        Long foreign = media(user("MERCHANT"));
        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("categoryId", child.getId(), "name", "越权图片",
                        "coverMediaId", foreign, "imageMediaIds", List.of(foreign)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onSaleMustRetainCoverAndImages() throws Exception {
        Long owner = user("MERCHANT");
        Category child = child();
        Product product = product(owner, child, "ON_SALE");
        image(product, media(owner), true, 0);
        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body(child.getId(), "保留图片")))
                .andExpect(status().isOk());
        mvc.perform(put("/merchant/products/{id}", product.getId()).session(session(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("categoryId", child.getId(), "name", "清空图片",
                        "imageMediaIds", List.of()))))
                .andExpect(status().isConflict());
        assertEquals("保留图片", products.selectById(product.getId()).getName());
        assertEquals(1, images(product).size());
    }

    private String body(Long categoryId, String name) { return json.writeValueAsString(Map.of("categoryId", categoryId, "name", name)); }
    private MockHttpSession session(Long id) { MockHttpSession s = new MockHttpSession(); s.setAttribute("userId", id); return s; }
    private Long user(String role) {
        String name = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(name, "Shop123!", null));
        jdbc.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, name);
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, name);
    }
    private Category child() {
        Category root = category(null, 1);
        return category(root.getId(), 2);
    }
    private Category category(Long parent, int level) {
        Category c = new Category(); c.setParentId(parent); c.setLevel(level); c.setName("分类-" + UUID.randomUUID());
        c.setVisible(1); c.setSortOrder(0); categories.insert(c); return c;
    }
    private Brand brand() {
        Brand b = new Brand(); b.setName("品牌-" + UUID.randomUUID()); b.setEnabled(1); brands.insert(b); return b;
    }
    private Product product(Long owner, Category category, String status) {
        Shop shop = new Shop(); shop.setOwnerUserId(owner); shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(1); shops.insert(shop);
        Product p = new Product(); p.setShopId(shop.getId()); p.setCategoryId(category.getId());
        p.setName("商品-" + UUID.randomUUID()); p.setStatus(ProductStatus.valueOf(status)); products.insert(p); return p;
    }
    private Long media(Long owner) {
        String key = "test/product-update-" + UUID.randomUUID();
        jdbc.update("INSERT INTO media_assets(uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status) " +
                "VALUES (?,?,'image/png',10,1,1,UNHEX(SHA2(?,256)),'READY')", owner, key, key);
        return jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }
    private void image(Product product, Long mediaId, boolean cover, int order) {
        ProductImage i = new ProductImage(); i.setProductId(product.getId()); i.setMediaId(mediaId);
        i.setIsCover(cover ? 1 : 0); i.setSortOrder(order); productImages.insert(i);
    }
    private List<ProductImage> images(Product product) {
        return productImages.selectList(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, product.getId()).orderByAsc(ProductImage::getSortOrder));
    }
}
