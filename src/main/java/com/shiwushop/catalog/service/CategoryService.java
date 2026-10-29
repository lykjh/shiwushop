package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.category.CategoryCreateDTO;
import com.shiwushop.catalog.dto.category.CategoryUpdateDTO;
import com.shiwushop.catalog.vo.category.CategoryDetailVO;
import com.shiwushop.catalog.vo.category.CategoryNavVO;
import com.shiwushop.catalog.vo.category.CategoryVO;


import java.util.List;

public interface CategoryService {
    CategoryVO create(CategoryCreateDTO dto);
    List<CategoryVO> adminList();

    CategoryVO update(Long id, CategoryUpdateDTO dto);

    void delete(Long id);

    CategoryDetailVO detail(Long id);

    List<CategoryNavVO> list(Long parentId);
}
