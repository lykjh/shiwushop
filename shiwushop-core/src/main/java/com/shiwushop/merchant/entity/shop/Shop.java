package com.shiwushop.merchant.entity.shop;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;

@Data
@TableName("shops")
public class Shop {

    /** 店铺 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 店主用户 ID。 */
    private Long ownerUserId;
    /** 店铺名称。 */
    private String name;
    /** 标识图片的媒体 ID。 */
    private Long logoMediaId;
    /** 标识图片展示地址；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String logoUrl;
    /** 展示说明。 */
    private String description;
    /** 是否启用，1 是、0 否。 */
    private Integer enabled;
    /** 经营资质有效截止日期，包含当天。 */
    private LocalDate qualificationValidUntil;
    /** 店铺经营资质是否已撤销，1 是、0 否。 */
    private Integer qualificationRevoked;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;

    /** 到期日包含当天；存量店铺没有资质期限。 */
    public boolean isTradingEnabled() {
        return Integer.valueOf(1).equals(enabled) && isQualificationCurrent();
    }

    public boolean isQualificationCurrent() {
        return !Integer.valueOf(1).equals(qualificationRevoked)
                && (qualificationValidUntil == null
                    || !qualificationValidUntil.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
    }
}
