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

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantProductDetailTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
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
    void returnsFullDraftInDisplayOrder() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));
        Long secondMediaId = image(product, owner, 1);
        Long firstMediaId = image(product, owner, 0);
        ProductOption capacity = option(product, "容量", 1);
        ProductOption color = option(product, "颜色", 0);
        ProductOptionValue small = value(capacity, "128GB", 0);
        ProductOptionValue white = value(color, "白色", 0);
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        sku.setOptionSignature(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((white.getId() + "," + small.getId()).getBytes(StandardCharsets.UTF_8))));
        sku.setPrice(new BigDecimal("3999.00"));
        sku.setStock(0);
        skuMapper.insert(sku);
        linkMapper.insertLink(sku.getId(), small.getId());
        linkMapper.insertLink(sku.getId(), white.getId());

        mockMvc.perform(get("/merchant/products/{id}", product.getId()).session(session(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(product.getId()))
                .andExpect(jsonPath("$.data.shopId").value(product.getShopId()))
                .andExpect(jsonPath("$.data.categoryId").value(product.getCategoryId()))
                .andExpect(jsonPath("$.data.name").value(product.getName()))
                .andExpect(jsonPath("$.data.description").value("商品介绍"))
                .andExpect(jsonPath("$.data.coverMediaId").value(firstMediaId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.imageMediaIds[0]").value(firstMediaId))
                .andExpect(jsonPath("$.data.imageMediaIds[1]").value(secondMediaId))
                .andExpect(jsonPath("$.data.options[0].id").value(color.getId()))
                .andExpect(jsonPath("$.data.options[0].values[0].id").value(white.getId()))
                .andExpect(jsonPath("$.data.options[1].id").value(capacity.getId()))
                .andExpect(jsonPath("$.data.options[1].values[0].id").value(small.getId()))
                .andExpect(jsonPath("$.data.skus[0].id").value(sku.getId()))
                .andExpect(jsonPath("$.data.skus[0].optionValueIds[0]").value(white.getId()))
                .andExpect(jsonPath("$.data.skus[0].optionValueIds[1]").value(small.getId()))
                .andExpect(jsonPath("$.data.skus[0].price").value(3999.00))
                .andExpect(jsonPath("$.data.skus[0].stock").value(0));
    }

    @Test
    void rejectsAnonymousForeignAndMissingProducts() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));

        mockMvc.perform(get("/merchant/products/{id}", product.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/merchant/products/{id}", product.getId())
                        .session(session(user("MERCHANT"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/merchant/products/{id}", product.getId())
                        .session(session(user("USER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/merchant/products/{id}", Long.MAX_VALUE)
                        .session(session(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/merchant/products/{id}", 0)
                        .session(session(owner)))
                .andExpect(status().isBadRequest());
    }

    private Long user(String role) {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private Shop shop(Long owner) {
        Shop shop = new Shop();
        shop.setOwnerUserId(owner);
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        return shop;
    }

    private Product product(Shop shop) {
        Category category = new Category();
        category.setName("分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(category.getId());
        product.setName("商品-" + UUID.randomUUID());
        product.setDescription("商品介绍");
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);
        return product;
    }

    private Long image(Product product, Long owner, int order) {
        String key = "test/product-detail-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO media_assets(uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status) " +
                "VALUES (?,?,'image/png',10,1,1,UNHEX(SHA2(?,256)),'READY')", owner, key, key);
        Long mediaId = jdbcTemplate.queryForObject("SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
        ProductImage image = new ProductImage();
        image.setProductId(product.getId());
        image.setMediaId(mediaId);
        image.setIsCover(order == 0 ? 1 : 0);
        image.setSortOrder(order);
        imageMapper.insert(image);
        return mediaId;
    }

    private ProductOption option(Product product, String name, int order) {
        ProductOption option = new ProductOption();
        option.setProductId(product.getId());
        option.setName(name);
        option.setSortOrder(order);
        optionMapper.insert(option);
        return option;
    }

    private ProductOptionValue value(ProductOption option, String name, int order) {
        ProductOptionValue value = new ProductOptionValue();
        value.setOptionId(option.getId());
        value.setValueName(name);
        value.setSortOrder(order);
        valueMapper.insert(value);
        return value;
    }

    private MockHttpSession session(Long user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }
}
