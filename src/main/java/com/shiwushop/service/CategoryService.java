package com.shiwushop.service;

import com.shiwushop.dto.category.CategoryCreateDTO;
import com.shiwushop.dto.category.CategoryUpdateDTO;
import com.shiwushop.vo.category.CategoryDetailVO;
import com.shiwushop.vo.category.CategoryNavVO;
import com.shiwushop.vo.category.CategoryVO;


import java.util.List;

public interface CategoryService {
    CategoryVO create(CategoryCreateDTO dto);
    List<CategoryVO> adminList();

    CategoryVO update(Long id, CategoryUpdateDTO dto);

    void delete(Long id);

    CategoryDetailVO detail(Long id);

    List<CategoryNavVO> list(Long parentId);
}
