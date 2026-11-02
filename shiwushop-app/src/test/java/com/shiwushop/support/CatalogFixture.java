package com.shiwushop.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;
import java.util.*;

/** 小型真实目录夹具；只清理本夹具记录，不对整个测试库执行 truncate。 */
public final class CatalogFixture implements AutoCloseable {
    public final long shop, category;
    private final long rootCategory;
    public final TestAccounts.Account merchant;
    private final JdbcTemplate jdbc;
    private final TestAccounts accounts;
    private final List<Long> products = new ArrayList<>();
    private final List<Long> children = new ArrayList<>();
    private final Map<Long,java.nio.file.Path> media = new HashMap<>();
    public CatalogFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc; accounts = new TestAccounts(jdbc); merchant = accounts.create("MERCHANT", "Shop123!");
        rootCategory = insert("INSERT INTO categories(name,level,visible,sort_order) VALUES (?,1,1,0)", "日用百货" + merchant.id());
        category = insert("INSERT INTO categories(name,parent_id,level,visible,sort_order) VALUES (?,?,2,1,0)", "家居日用",rootCategory);
        shop = insert("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", merchant.id(), "云杉生活馆" + merchant.id());
    }
    public long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i=0; i<args.length; i++) statement.setObject(i+1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public long product(String name, String status, String price, int stock) {
        long id = insert("INSERT INTO products(shop_id,category_id,name,status) VALUES (?,?,?,?)", shop, category, name, status);
        products.add(id);
        insert("INSERT INTO skus(product_id,option_signature,price,stock) VALUES (?,?,?,?)", id, String.format("%064x", id), price, stock);
        return id;
    }
    public long sku(long product) { return jdbc.queryForObject("SELECT id FROM skus WHERE product_id=?", Long.class, product); }
    public void trackProduct(long product) { products.add(product); }
    public void trackCategory(long category) { children.add(category); }
    public void trackMedia(long id, java.nio.file.Path root) { media.put(id,root); }
    @Override public void close() {
        for (long id : products) {
            jdbc.update("DELETE FROM product_review_summaries WHERE product_id=?", id);
            jdbc.update("DELETE FROM product_sales_stats WHERE product_id=?", id);
            jdbc.update("DELETE FROM product_brands WHERE product_id=?", id);
            jdbc.update("DELETE FROM product_images WHERE product_id=?", id);
            jdbc.update("DELETE sov FROM sku_option_values sov JOIN skus s ON s.id=sov.sku_id WHERE s.product_id=?",id);
            jdbc.update("DELETE v FROM product_option_values v JOIN product_options o ON o.id=v.option_id WHERE o.product_id=?",id);
            jdbc.update("DELETE FROM product_options WHERE product_id=?",id);
            jdbc.update("DELETE FROM skus WHERE product_id=?", id);
            jdbc.update("DELETE FROM products WHERE id=?", id);
        }
        for(var item:media.entrySet()) {
            String key=jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id=?",String.class,item.getKey());
            var file=item.getValue().resolve(key).normalize();
            if(!file.startsWith(item.getValue()))throw new IllegalStateException("图片路径越界");
            try{java.nio.file.Files.deleteIfExists(file);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            jdbc.update("DELETE FROM media_assets WHERE id=?",item.getKey());
        }
        jdbc.update("DELETE FROM shops WHERE id=?", shop);
        Collections.reverse(children);for(long child:children)jdbc.update("DELETE FROM categories WHERE id=?",child);
        jdbc.update("DELETE FROM categories WHERE id=?", category);
        jdbc.update("DELETE FROM categories WHERE id=?", rootCategory);
        accounts.close();
    }
}
