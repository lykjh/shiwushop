package com.shiwushop.product.entity.brand;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("brands")
public class Brand {

    /** 品牌 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 品牌名称。 */
    private String name;
    /** 标识图片的媒体 ID。 */
    private Long logoMediaId;
    /** 标识图片展示地址；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private String logoUrl;
    /** 是否启用，1 是、0 否。 */
    private Integer enabled;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
