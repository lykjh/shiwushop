package com.shiwushop.commerce.mapper.shipment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.commerce.entity.shipment.ReturnShipment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface ReturnShipmentMapper extends BaseMapper<ReturnShipment> {
    @Select("SELECT * FROM return_shipments WHERE case_id=#{caseId}")
    ReturnShipment selectByCaseId(@Param("caseId") Long caseId);

    @Update("""
            UPDATE return_shipments SET received_at=#{now}, received_by_user_id=#{merchantId}
            WHERE case_id=#{caseId} AND received_at IS NULL
            """)
    int markReceived(@Param("caseId") Long caseId, @Param("merchantId") Long merchantId,
                     @Param("now") LocalDateTime now);
}
