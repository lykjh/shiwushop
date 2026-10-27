package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.brand.Brand;
import com.shiwushop.entity.category.Category;
import com.shiwushop.entity.product.Product;
import com.shiwushop.entity.product.ProductImage;
import com.shiwushop.entity.product.Sku;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.mapper.brand.BrandMapper;
import com.shiwushop.mapper.category.CategoryMapper;
import com.shiwushop.mapper.product.ProductImageMapper;
import com.shiwushop.mapper.product.ProductMapper;
import com.shiwushop.mapper.product.SkuMapper;
import com.shiwushop.mapper.shop.ShopMapper;

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.service.UserService;
import com.shiwushop.vo.query.product.PublicProductRow;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "shop.public-cache.ttl-seconds=1")
@AutoConfigureMockMvc
@Transactional
class PublicProductListTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryMapper categories;
    @Autowired private BrandMapper brands;
    @Autowired private ShopMapper shops;
    @Autowired private ProductMapper products;
    @Autowired private SkuMapper skus;
    @Autowired private ProductImageMapper images;

    @Test
    void filtersVisibleProductsAndUsesNewBrandMediaSalesTables() throws Exception {
        Long owner = user();
        Category root = category(null, 1, 1), child = category(root.getId(), 2, 1);
        Category hidden = category(null, 1, 0);
        Brand brand = brand();
        Shop open = shop(owner, 1), closed = shop(owner, 0);
        Product available = product(child, open, brand, "ON_SALE");
        Long cover = cover(available, owner);
        sku(available, "20.00", 0);
        sku(available, "30.00", 2);
        jdbc.update("INSERT INTO product_sales_stats(product_id,sold_count) VALUES (?,3)", available.getId());
        Product soldOut = product(child, open, null, "ON_SALE");
        sku(soldOut, "10.00", 0);
        sku(product(hidden, open, null, "ON_SALE"), "1.00", 1);
        sku(product(child, closed, null, "ON_SALE"), "1.00", 1);
        sku(product(child, open, null, "DRAFT"), "1.00", 1);

        var verified = products.selectPublicCardsByIds(
                List.of(available.getId(), soldOut.getId()), root.getId(), brand.getId(),
                open.getId(), new BigDecimal("25"), new BigDecimal("40"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(available.getId()),
                verified.stream().map(PublicProductRow::getId).toList());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("30.00"), verified.getFirst().getStartingPrice());

        mvc.perform(get("/products").param("categoryId", root.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.page").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].id").value(soldOut.getId()))
                .andExpect(jsonPath("$.data.items[0].startingPrice").value(10.0))
                .andExpect(jsonPath("$.data.items[0].soldOut").value(true))
                .andExpect(jsonPath("$.data.items[1].id").value(available.getId()))
                .andExpect(jsonPath("$.data.items[1].coverMediaId").value(cover))
                .andExpect(jsonPath("$.data.items[1].startingPrice").value(30.0))
                .andExpect(jsonPath("$.data.items[1].soldCount").value(3))
                .andExpect(jsonPath("$.data.items[1].brand.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.items[1].stock").doesNotExist());

        mvc.perform(get("/products").param("categoryId", root.getId().toString())
                        .param("brandId", brand.getId().toString())
                        .param("shopId", open.getId().toString())
                        .param("minPrice", "25").param("maxPrice", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(available.getId()));
        mvc.perform(get("/products").param("categoryId", root.getId().toString())
                        .param("minPrice", "15").param("maxPrice", "25"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));

        mvc.perform(get("/products").param("categoryId", root.getId().toString())
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value(soldOut.getId()))
                .andExpect(jsonPath("$.data.items[0].id").value(soldOut.getId()));
        mvc.perform(get("/products").param("categoryId", root.getId().toString())
                        .param("size", "1").param("afterId", soldOut.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(available.getId()))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        mvc.perform(get("/products").param("shopId", open.getId().toString()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(soldOut.getId()))
                .andExpect(jsonPath("$.data.nextCursor").value(soldOut.getId()));
        mvc.perform(get("/products").param("shopId", open.getId().toString())
                        .param("size", "1").param("afterId", soldOut.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(available.getId()))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/products").param("shopId", open.getId().toString())
                        .param("categoryId", root.getId().toString()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").value(soldOut.getId()));
        mvc.perform(get("/products").param("shopId", open.getId().toString())
                        .param("categoryId", root.getId().toString())
                        .param("size", "1").param("afterId", soldOut.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(available.getId()))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/products").param("shopId", open.getId().toString())
                        .param("minPrice", "25").param("maxPrice", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(available.getId()));
    }

    @Test
    void validatesFiltersAndRejectsNumberedPages() throws Exception {
        Category hidden = category(null, 1, 0);
        mvc.perform(get("/products").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("page", "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("includeTotal", "true")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("afterId", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("afterId", "1").param("page", "2"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("minPrice", "20").param("maxPrice", "10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("categoryId", hidden.getId().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/products").param("brandId", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void filteredPriceAndOffSaleChangesAreVisibleWithoutPageCache() throws Exception {
        Long owner = user();
        Category root = category(null, 1, 1);
        Category child = category(root.getId(), 2, 1);
        Product product = product(child, shop(owner, 1), null, "ON_SALE");
        sku(product, "10.00", 1);
        String categoryId = child.getId().toString();

        mvc.perform(get("/products").param("categoryId", categoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].startingPrice").value(10.0));
        Sku changedSku = skus.selectById(jdbc.queryForObject(
                "SELECT id FROM skus WHERE product_id=?", Long.class, product.getId()));
        changedSku.setPrice(new BigDecimal("20.00"));
        skus.updateById(changedSku);
        mvc.perform(get("/products").param("categoryId", categoryId))
                .andExpect(jsonPath("$.data.items[0].startingPrice").value(20.0));

        product.setStatus(ProductStatus.OFF_SALE);
        products.updateById(product);
        mvc.perform(get("/products").param("categoryId", categoryId))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    private Long user() {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }
    private Category category(Long parent, int level, int visible) {
        Category c = new Category(); c.setName("分类-" + UUID.randomUUID()); c.setParentId(parent);
        c.setLevel(level); c.setVisible(visible); c.setSortOrder(0); categories.insert(c); return c;
    }
    private Brand brand() {
        Brand b = new Brand(); b.setName("品牌-" + UUID.randomUUID()); b.setEnabled(1); brands.insert(b); return b;
    }
    private Shop shop(Long owner, int enabled) {
        Shop s = new Shop(); s.setOwnerUserId(owner); s.setName("店铺-" + UUID.randomUUID());
        s.setEnabled(enabled); shops.insert(s); return s;
    }
    private Product product(Category category, Shop shop, Brand brand, String status) {
        Product p = new Product(); p.setCategoryId(category.getId()); p.setShopId(shop.getId());
        p.setName("商品-" + UUID.randomUUID()); p.setStatus(ProductStatus.valueOf(status)); products.insert(p);
        if (brand != null) products.insertBrand(p.getId(), brand.getId());
        return p;
    }
    private void sku(Product product, String price, int stock) {
        Sku s = new Sku(); s.setProductId(product.getId());
        String hex = UUID.randomUUID().toString().replace("-", "");
        s.setOptionSignature(hex + hex); s.setPrice(new BigDecimal(price)); s.setStock(stock); skus.insert(s);
    }
    private Long cover(Product product, Long owner) {
        String key = "test/public-product-" + UUID.randomUUID();
        jdbc.update("INSERT INTO media_assets(uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status) " +
                "VALUES (?,?,'image/png',10,1,1,UNHEX(SHA2(?,256)),'READY')", owner, key, key);
        Long mediaId = jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
        ProductImage image = new ProductImage(); image.setProductId(product.getId()); image.setMediaId(mediaId);
        image.setIsCover(1); image.setSortOrder(0); images.insert(image); return mediaId;
    }
}
