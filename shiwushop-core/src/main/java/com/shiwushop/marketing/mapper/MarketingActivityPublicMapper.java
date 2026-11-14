package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.model.*;
import com.shiwushop.marketing.vo.ActivityPublicVO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingActivityPublicMapper {
    @Select("""
            <script>SELECT a.* FROM marketing_activities a WHERE a.status IN ('APPROVED','RUNNING') AND a.end_at>#{now}
            <if test='afterId!=null'>AND a.id &lt; #{afterId}</if><if test='type!=null'>AND a.activity_type=#{type}</if>
            <if test='shop!=null'>AND EXISTS(SELECT 1 FROM marketing_budgets b WHERE b.activity_id=a.id AND b.shop_id=#{shop})</if>
            <if test='sku!=null'>AND EXISTS(SELECT 1 FROM marketing_activity_skus s WHERE s.activity_id=a.id AND s.sku_id=#{sku})</if>
            ORDER BY a.id DESC LIMIT #{limit}</script>
            """)
    List<MarketingActivity> list(@Param("afterId") Long afterId, @Param("limit") int limit, @Param("type") String type,
                                 @Param("shop") Long shop, @Param("sku") Long sku, @Param("now") LocalDateTime now);

    @Select("""
            SELECT s.*,sp.price silver,gp.price gold,pp.price platinum FROM marketing_activity_skus s
            LEFT JOIN marketing_member_prices sp ON sp.activity_sku_id=s.id AND sp.level_code='SILVER'
            LEFT JOIN marketing_member_prices gp ON gp.activity_sku_id=s.id AND gp.level_code='GOLD'
            LEFT JOIN marketing_member_prices pp ON pp.activity_sku_id=s.id AND pp.level_code='PLATINUM'
            WHERE s.activity_id=#{id} ORDER BY s.id
            """)
    List<ActivityScope> scopes(Long id);

    @Select("""
            SELECT t.id,t.threshold_amount,t.reduction_amount,s.sku_id gift_sku_id,t.gift_quantity FROM marketing_threshold_tiers t
            LEFT JOIN marketing_activity_skus s ON s.id=t.gift_activity_sku_id WHERE t.activity_id=#{id} ORDER BY t.id
            """)
    List<ActivityPublicVO.Tier> tiers(Long id);

    @Select("""
            SELECT face_amount,threshold_amount,issue_limit,per_user_limit,claim_end_at,validity_days,fixed_expire_at,
              grant_mode,member_level_code FROM marketing_coupon_rules WHERE activity_id=#{id}
            """)
    ActivityCouponRule coupon(Long id);
}
