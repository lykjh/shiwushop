package com.shiwushop.mapper.after_sale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.entity.after_sale.AfterSaleCase;
import com.shiwushop.entity.status.AfterSaleStatus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AfterSaleCaseMapper extends BaseMapper<AfterSaleCase> {
    @Select("SELECT * FROM after_sale_cases WHERE id=#{id} FOR UPDATE")
    AfterSaleCase selectForUpdate(@Param("id") Long id);

    List<Long> selectBuyerIds(@Param("orderId") Long orderId, @Param("beforeId") Long beforeId,
                              @Param("limit") int limit);
    List<Long> selectMerchantIds(@Param("shopId") Long shopId, @Param("beforeId") Long beforeId,
                                 @Param("status") AfterSaleStatus status, @Param("limit") int limit);

    @Select("SELECT * FROM after_sale_cases WHERE order_id=#{orderId} AND request_key=#{requestKey}")
    AfterSaleCase selectByRequestKey(@Param("orderId") Long orderId, @Param("requestKey") String requestKey);

    int review(@Param("id") Long id, @Param("next") AfterSaleStatus next,
               @Param("reviewer") Long reviewer, @Param("reason") String reason,
               @Param("now") LocalDateTime now, @Param("finishedAt") LocalDateTime finishedAt);

    @Update("UPDATE after_sale_cases SET status=#{next} WHERE id=#{id} AND status=#{expected}")
    int transition(@Param("id") Long id, @Param("expected") AfterSaleStatus expected,
                   @Param("next") AfterSaleStatus next);

    int markRefunded(@Param("id") Long id, @Param("now") LocalDateTime now);
}
