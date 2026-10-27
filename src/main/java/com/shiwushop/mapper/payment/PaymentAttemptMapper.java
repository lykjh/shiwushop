package com.shiwushop.mapper.payment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.entity.payment.PaymentAttempt;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface PaymentAttemptMapper extends BaseMapper<PaymentAttempt> {
    @Select("SELECT * FROM payment_attempts WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1")
    PaymentAttempt selectLatest(@Param("orderId") Long orderId);

    @Select("SELECT * FROM payment_attempts WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    PaymentAttempt selectLatestForUpdate(@Param("orderId") Long orderId);

    @Select("SELECT * FROM payment_attempts WHERE out_trade_no = #{outTradeNo}")
    PaymentAttempt selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    int markSuccess(@Param("id") Long id, @Param("tradeNo") String tradeNo,
                    @Param("paidAt") LocalDateTime paidAt);

    int markClosed(@Param("id") Long id);

    @Update("UPDATE payment_attempts SET qr_code = #{qrCode}, qr_expires_at = #{expiresAt}, "
            + "qr_claim_token = NULL, qr_claim_expires_at = NULL "
            + "WHERE id = #{id} AND status = 'PENDING' AND qr_code IS NULL AND qr_claim_token = #{token}")
    int storeQrCode(@Param("id") Long id, @Param("qrCode") String qrCode,
                    @Param("expiresAt") LocalDateTime expiresAt, @Param("token") String token);

    @Update("UPDATE payment_attempts SET qr_claim_token = #{token}, qr_claim_expires_at = #{expiresAt} "
            + "WHERE id = #{id} AND status = 'PENDING' AND qr_code IS NULL "
            + "AND (qr_claim_expires_at IS NULL OR qr_claim_expires_at <= #{now})")
    int claimQrCode(@Param("id") Long id, @Param("token") String token,
                    @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);
}
