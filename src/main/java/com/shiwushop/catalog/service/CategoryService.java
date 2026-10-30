package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.category.CategoryCreateDTO;
import com.shiwushop.catalog.dto.category.CategoryUpdateDTO;
import com.shiwushop.catalog.vo.category.CategoryDetailVO;
import com.shiwushop.catalog.vo.category.CategoryNavVO;
import com.shiwushop.catalog.vo.category.CategoryVO;


import java.util.List;

public interface CategoryService {
    /** 创建分类并检查其父分类与层级约束。 */
    CategoryVO create(CategoryCreateDTO dto);
    /** 返回管理员可见的全部分类。 */
    List<CategoryVO> adminList();

    /** 更新分类资料或层级关系，同时避免形成分类环。 */
    CategoryVO update(Long id, CategoryUpdateDTO dto);

    /** 在符合关联约束时删除指定分类。 */
    void delete(Long id);

    /** 返回指定可见分类的公开详情。 */
    CategoryDetailVO detail(Long id);

    /** 返回顶层分类或指定父分类下的公开导航项。 */
    List<CategoryNavVO> list(Long parentId);
}
