package com.shiwushop.system.catalog;

import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductPublishingSystemIT {
    @Test void uploadedProductBecomesPublicAndTransferRevokesOldOwnerWrites() throws Exception {
        String base=SystemEnvironment.base();var jdbc=SystemEnvironment.database();var json=new ObjectMapper();
        Path root=Path.of(Objects.requireNonNull(System.getProperty("system.media-root"),"必须指定独立实例的图片目录")).toAbsolutePath().normalize();
        assertTrue(root.startsWith(Path.of("target").toAbsolutePath().normalize()));
        var catalog=new CatalogFixture(jdbc);var accounts=new TestAccounts(jdbc);
        var admin=accounts.create("ADMIN","Admin123!");var newOwner=accounts.create("MERCHANT","Shop456!");
        long child=0,media=0,product=0;
        try(var manager=CommerceFixture.login(base,admin);var merchant=CommerceFixture.login(base,catalog.merchant);
            var anonymous=new HttpAccountClient(base)) {
            var category=manager.post("/admin/categories",Map.of("name","家用毛巾","parentId",catalog.category,"visible",1));
            assertEquals(200,category.statusCode(),category.body());child=json.readTree(category.body()).path("data").path("id").asLong();catalog.trackCategory(child);
            var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",bytes);
            var upload=merchant.upload("/media",bytes.toByteArray(),"毛巾.png","image/png");assertEquals(200,upload.statusCode(),upload.body());
            media=json.readTree(upload.body()).path("data").path("id").asLong();catalog.trackMedia(media,root);
            var created=merchant.post("/merchant/products",Map.of("shopId",catalog.shop,"categoryId",child,"name","洁丽雅纯棉毛巾",
                    "coverMediaId",media,"imageMediaIds",List.of(media)));assertEquals(200,created.statusCode(),created.body());
            product=json.readTree(created.body()).path("data").path("id").asLong();catalog.trackProduct(product);
            var options=merchant.request("PUT","/merchant/products/"+product+"/options",Map.of("options",List.of(Map.of("name","颜色","values",List.of("白色")))));
            assertEquals(200,options.statusCode(),options.body());long value=json.readTree(options.body()).path("data").get(0).path("values").get(0).path("id").asLong();
            var sku=merchant.post("/merchant/products/"+product+"/skus",Map.of("optionValueIds",List.of(value),"price","19.90","stock",5));
            assertEquals(200,sku.statusCode(),sku.body());long skuId=json.readTree(sku.body()).path("data").path("id").asLong();
            var onSale=merchant.request("PUT","/merchant/products/"+product+"/status",Map.of("status","ON_SALE"));assertEquals(200,onSale.statusCode(),onSale.body());
            assertEquals(200,anonymous.get("/products/"+product).statusCode());assertEquals(200,anonymous.get("/media/"+media).statusCode());
            var transfer=manager.request("PUT","/admin/shops/"+catalog.shop+"/owner",Map.of("ownerUserId",newOwner.id()));assertEquals(200,transfer.statusCode(),transfer.body());
            var oldWrite=merchant.request("PUT","/merchant/products/"+product+"/skus/"+skuId,Map.of("price","29.90","stock",5));
            assertEquals(404,oldWrite.statusCode(),oldWrite.body());assertEquals("19.90",jdbc.queryForObject("SELECT price FROM skus WHERE id=?",java.math.BigDecimal.class,skuId).toPlainString());
        } finally {
            if(product>0)jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='PRODUCT' AND aggregate_id=?",product);
            if(child>0)jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='CATEGORY' AND aggregate_id=?",child);
            // 先移除商品和店铺，再清理其引用的图片和子分类。
            catalog.close();
            jdbc.update("DELETE FROM audit_events WHERE actor_user_id=?",admin.id());accounts.close();
        }
    }
}
