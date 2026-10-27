package com.shiwushop.entity.brand;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("brands")
public class Brand {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long logoMediaId;
    @TableField(exist = false)
    private String logoUrl;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
