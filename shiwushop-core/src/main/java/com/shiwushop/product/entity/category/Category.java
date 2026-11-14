package com.shiwushop.product.entity.category;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("categories")
public class Category {

    /** 分类 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父分类 ID；根分类为空。 */
    private Long parentId;
    /** 分类层级。 */
    private Integer level;
    /** 分类名称。 */
    private String name;
    /** 是否公开展示，1 是、0 否。 */
    private Integer visible;
    /** 排序值。 */
    private Integer sortOrder;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
