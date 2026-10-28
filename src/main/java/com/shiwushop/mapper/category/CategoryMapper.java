package com.shiwushop.mapper.category;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.entity.category.Category;
import org.apache.ibatis.annotations.Select;

public interface CategoryMapper extends BaseMapper<Category> {
    // 分类结构变更共用一行数据库锁，跨应用实例串行检查父链。
    @Select("SELECT id FROM categories ORDER BY id LIMIT 1 FOR UPDATE")
    Long lockTree();
}
