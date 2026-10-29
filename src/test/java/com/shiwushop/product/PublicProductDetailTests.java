package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.brand.Brand;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductImage;
import com.shiwushop.catalog.entity.product.ProductOption;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicProductDetailTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryMapper categories;
    @Autowired private BrandMapper brands;
    @Autowired private ShopMapper shops;
    @Autowired private ProductMapper products;
    @Autowired private ProductImageMapper images;
    @Autowired private ProductOptionMapper options;
    @MockitoSpyBean private ProductOptionValueMapper values;
    @Autowired private SkuMapper skus;
    @MockitoSpyBean private SkuOptionValueMapper links;

    @Test
    void showsMediaBrandOptionsSkuAndHistoricalSalesWithoutStock() throws Exception {
        Long owner = user();
        Shop shop = shop(owner, 1);
        Category root = category(null, 1, 1), child = category(root.getId(), 2, 1);
        Brand brand = brand();
        Product product = product(child, shop, brand, "ON_SALE");
        Long cover = image(product, owner, 0), detail = image(product, owner, 1);
        ProductOption option = option(product, "颜色", 0);
        ProductOptionValue white = value(option, "白色");
        Sku sku = sku(product, "3999.00", 2);
        links.insertLink(sku.getId(), white.getId());
        jdbc.update("INSERT INTO product_sales_stats(product_id,sold_count) VALUES (?,2)", product.getId());

        mvc.perform(get("/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverMediaId").value(cover))
                .andExpect(jsonPath("$.data.imageMediaIds[0]").value(cover))
                .andExpect(jsonPath("$.data.imageMediaIds[1]").value(detail))
                .andExpect(jsonPath("$.data.categoryPath[0].id").value(root.getId()))
                .andExpect(jsonPath("$.data.categoryPath[1].id").value(child.getId()))
                .andExpect(jsonPath("$.data.brand.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.shop.id").value(shop.getId()))
                .andExpect(jsonPath("$.data.shop.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.data.soldCount").value(2))
                .andExpect(jsonPath("$.data.options[0].values[0].value").value("白色"))
                .andExpect(jsonPath("$.data.skus[0].optionValueIds[0]").value(white.getId()))
                .andExpect(jsonPath("$.data.skus[0].available").value(true))
                .andExpect(jsonPath("$.data.skus[0].stock").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist());
    }

    @Test
    void soldOutUnbrandedProductHasZeroSales() throws Exception {
        Long owner = user();
        Product product = product(category(null, 1, 1), shop(owner, 1), null, "ON_SALE");
        sku(product, "100.00", 0);
        mvc.perform(get("/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brand").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.soldCount").value(0))
                .andExpect(jsonPath("$.data.skus[0].available").value(false));
    }

    @Test
    void oneTwentyAndHundredSkusUseOneOptionValueAndOneSkuLinkQuery() throws Exception {
        Long owner = user();
        Product product = product(category(null, 1, 1), shop(owner, 1), null, "ON_SALE");
        ProductOption option = option(product, "颜色", 0);
        ProductOptionValue value = value(option, "白色");
        int previous = 0;
        for (int count : new int[]{1, 20, 100}) {
            for (int i = previous; i < count; i++) {
                Sku sku = sku(product, "100.00", 2);
                links.insertLink(sku.getId(), value.getId());
            }
            previous = count;
            clearInvocations(values, links);
            mvc.perform(get("/products/{id}", product.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.skus.length()").value(count));
            verify(values, times(1)).selectList(any());
            verify(links, times(1)).selectSkuValues(anyList());
        }
    }

    @Test
    void hidesDraftStoppedShopAndHiddenCategoryPath() throws Exception {
        Long owner = user();
        Shop open = shop(owner, 1), stopped = shop(owner, 0);
        Category visible = category(null, 1, 1), hidden = category(null, 1, 0);
        Category child = category(hidden.getId(), 2, 1);
        for (Long id : new Long[]{
                product(visible, open, null, "DRAFT").getId(),
                product(visible, open, null, "OFF_SALE").getId(),
                product(visible, stopped, null, "ON_SALE").getId(),
                product(child, open, null, "ON_SALE").getId(), Long.MAX_VALUE}) {
            mvc.perform(get("/products/{id}", id)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/products/{id}", 0)).andExpect(status().isBadRequest());
    }

    private Long user() {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }
    private Shop shop(Long owner, int enabled) {
        Shop s = new Shop(); s.setOwnerUserId(owner); s.setName("店铺-" + UUID.randomUUID());
        s.setEnabled(enabled); shops.insert(s); return s;
    }
    private Category category(Long parent, int level, int visible) {
        Category c = new Category(); c.setParentId(parent); c.setLevel(level);
        c.setVisible(visible); c.setSortOrder(0); c.setName("分类-" + UUID.randomUUID());
        categories.insert(c); return c;
    }
    private Brand brand() {
        Brand b = new Brand(); b.setName("品牌-" + UUID.randomUUID()); b.setEnabled(1); brands.insert(b); return b;
    }
    private Product product(Category category, Shop shop, Brand brand, String status) {
        Product p = new Product(); p.setCategoryId(category.getId()); p.setShopId(shop.getId());
        p.setName("商品-" + UUID.randomUUID()); p.setStatus(ProductStatus.valueOf(status)); products.insert(p);
        if (brand != null) products.insertBrand(p.getId(), brand.getId());
        return p;
    }
    private Long image(Product product, Long owner, int order) {
        String key = "test/product-detail-" + UUID.randomUUID();
        jdbc.update("INSERT INTO media_assets(uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status) " +
                "VALUES (?,?,'image/png',10,1,1,UNHEX(SHA2(?,256)),'READY')", owner, key, key);
        Long mediaId = jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
        ProductImage image = new ProductImage(); image.setProductId(product.getId()); image.setMediaId(mediaId);
        image.setIsCover(order == 0 ? 1 : 0); image.setSortOrder(order); images.insert(image); return mediaId;
    }
    private ProductOption option(Product product, String name, int order) {
        ProductOption o = new ProductOption(); o.setProductId(product.getId()); o.setName(name);
        o.setSortOrder(order); options.insert(o); return o;
    }
    private ProductOptionValue value(ProductOption option, String name) {
        ProductOptionValue v = new ProductOptionValue(); v.setOptionId(option.getId());
        v.setValueName(name); v.setSortOrder(0); values.insert(v); return v;
    }
    private Sku sku(Product product, String price, int stock) {
        Sku s = new Sku(); s.setProductId(product.getId());
        String hex = UUID.randomUUID().toString().replace("-", ""); s.setOptionSignature(hex + hex);
        s.setPrice(new BigDecimal(price)); s.setStock(stock); skus.insert(s); return s;
    }
}
