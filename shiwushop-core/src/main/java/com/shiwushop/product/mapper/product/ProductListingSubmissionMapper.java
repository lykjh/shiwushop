package com.shiwushop.product.mapper.product;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.product.entity.product.ProductListingSubmission;
import com.shiwushop.product.vo.product.PendingProductListingPage.Item;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 保存商品上架申请及其审核修订版本。 */
public interface ProductListingSubmissionMapper extends BaseMapper<ProductListingSubmission> {
    String PENDING = """
            FROM product_listing_submissions s
            JOIN products p ON p.id = s.product_id AND p.revision = s.revision
            WHERE s.status = 'PENDING' AND p.status = 'PENDING_REVIEW'
            """;

    @Select("SELECT COUNT(*) " + PENDING)
    long countPending();

    @Select("SELECT s.product_id AS productId, p.shop_id AS shopId, p.name, s.revision,"
            + " s.submitted_at AS submittedAt " + PENDING + " ORDER BY s.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Item> listPending(@Param("offset") long offset, @Param("size") int size);

    @Select("SELECT s.product_id AS productId, p.shop_id AS shopId, p.name, s.revision,"
            + " s.submitted_at AS submittedAt " + PENDING + " AND s.product_id = #{productId}")
    Item findPending(@Param("productId") Long productId);

    @Select("SELECT COUNT(*) " + PENDING + """
            AND s.product_id = #{productId} AND s.revision = #{revision}
            AND EXISTS (SELECT 1 FROM product_images pi WHERE pi.product_id = p.id AND pi.media_id = #{mediaId})
            """)
    boolean hasPendingImage(@Param("productId") Long productId, @Param("revision") Long revision,
                            @Param("mediaId") Long mediaId);

    /** 按商品 ID 和修订号加锁读取上架申请。 */
    @Select("SELECT * FROM product_listing_submissions WHERE product_id=#{productId} AND revision=#{revision} FOR UPDATE")
    ProductListingSubmission selectForUpdate(@Param("productId") Long productId, @Param("revision") Long revision);
}
