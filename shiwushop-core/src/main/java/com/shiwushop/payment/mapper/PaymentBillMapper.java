package com.shiwushop.payment.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.payment.entity.PaymentBill;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
public interface PaymentBillMapper extends BaseMapper<PaymentBill> {
 @Select("SELECT * FROM payment_bills WHERE channel=#{channel} AND app_id=#{appId} AND seller_id=#{sellerId} AND bill_type='trade' AND bill_date=#{date}")
 PaymentBill findDaily(@Param("channel") String channel,@Param("appId") String appId,@Param("sellerId") String sellerId,@Param("date") LocalDate date);
}
