package com.shiwushop.service.impl;

import com.shiwushop.dto.category.CategoryCreateDTO;
import com.shiwushop.dto.category.CategoryUpdateDTO;
import com.shiwushop.vo.category.CategoryDetailVO;
import com.shiwushop.vo.category.CategoryNavVO;
import com.shiwushop.vo.category.CategoryPathItemVO;
import com.shiwushop.vo.category.CategoryVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.entity.category.Category;
import com.shiwushop.mapper.category.CategoryMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final OutboxEventMapper outboxEventMapper;

    @Override
    @Transactional
    public CategoryVO create(CategoryCreateDTO dto) {
        String name = dto.getName().strip();

        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50) {
            throw new BusinessException(400, "分类名称长度必须在1到50个字符之间");
        }

        Category parent = null;
        if(dto.getParentId() != null){
            parent = categoryMapper.selectById(dto.getParentId());
            if(parent==null){
                throw new BusinessException(404, "父分类不存在");
            }
            if(parent.getLevel()>=3){
                throw new BusinessException(400, "父分类级别不能超过3级");
            }
        }


        Category category = new Category();
        category.setParentId(dto.getParentId());
        category.setLevel(parent == null ? 1 : parent.getLevel() + 1);
        category.setName(name);
        category.setVisible(dto.getVisible()==null ? 1 : dto.getVisible());
        category.setSortOrder(dto.getSortOrder()==null ? 0 : dto.getSortOrder());

        try {
            categoryMapper.insert(category);
            outboxEventMapper.insertSearchCategoryRefresh(category.getId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "分类名称已存在", e);
        }
        return toVO(category);
    }


    @Override
    public List<CategoryVO> adminList() {
        return categoryMapper.selectList(
                        new LambdaQueryWrapper<Category>()
                                .orderByAsc(Category::getLevel, Category::getSortOrder, Category::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional
    public CategoryVO update(Long id, CategoryUpdateDTO dto) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(404, "分类不存在");
        }
        String name = dto.getName().strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50) {
            throw new BusinessException(400, "分类名称长度必须在1到50个字符之间");
        }

        Category parent = validatedParent(id, dto.getParentId());
        int newLevel = parent == null ? 1 : parent.getLevel() + 1;

        List<Category> children = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().eq(Category::getParentId, id));
        List<Category> grandchildren = new ArrayList<>();
        for (Category child : children) {
            grandchildren.addAll(categoryMapper.selectList(
                    new LambdaQueryWrapper<Category>().eq(Category::getParentId, child.getId())));
        }

        // 移动分类时把子孙深度一并算入，确保整棵子树仍不超过三级。
        int descendantDepth = 0;
        if (!children.isEmpty()) descendantDepth = 1;
        if (!grandchildren.isEmpty()) descendantDepth = 2;
        if (newLevel + descendantDepth > 3) {
            throw new BusinessException(409, "父分类级别不能超过3级");
        }

        try {
            categoryMapper.update(null, new LambdaUpdateWrapper<Category>()
                    .eq(Category::getId, id)
                    .set(Category::getParentId, dto.getParentId())
                    .set(Category::getLevel, newLevel)
                    .set(Category::getName, name)
                    .set(Category::getVisible, dto.getVisible())
                    .set(Category::getSortOrder, dto.getSortOrder()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "分类名称已存在", e);
        }

        for (Category child : children) {
            categoryMapper.update(null, new LambdaUpdateWrapper<Category>()
                    .eq(Category::getId, child.getId())
                    .set(Category::getLevel, newLevel + 1));
        }
        for (Category grandchild : grandchildren) {
            categoryMapper.update(null, new LambdaUpdateWrapper<Category>()
                    .eq(Category::getId, grandchild.getId())
                    .set(Category::getLevel, newLevel + 2));
        }

        outboxEventMapper.insertSearchCategoryRefresh(id);
        return toVO(categoryMapper.selectById(id));
    }

    private Category validatedParent(Long id, Long parentId) {
        if (parentId == null) return null;
        Category parent = categoryMapper.selectById(parentId);
        if (parent == null) throw new BusinessException(404, "父分类不存在");
        // 沿父链检查，禁止把分类移动到自己的子孙下面形成环。
        Category current = parent;
        while (current != null) {
            if (current.getId().equals(id)) {
                throw new BusinessException(409, "父分类不能是自己的子分类");
            }
            current = current.getParentId() == null ? null : categoryMapper.selectById(current.getParentId());
        }
        return parent;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if(categoryMapper.selectById(id)==null){
            throw new BusinessException(404, "分类不存在");
        }

        if(categoryMapper.selectCount(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getParentId, id)) > 0){
            throw new BusinessException(409, "请先删除子分类");
        }

        try {
            categoryMapper.deleteById(id);
            outboxEventMapper.insertSearchCategoryRefresh(id);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "分类被引用，无法删除", e);
        }
    }

    @Override
    public CategoryDetailVO detail(Long id) {
        Category category = categoryMapper.selectById(id);
        if(category==null){
            throw new BusinessException(404, "分类不存在");
        }

        List<CategoryPathItemVO> path=new ArrayList<>();
        Category current = category;
        while (current != null) {
            if(!Integer.valueOf(1).equals(current.getVisible())){
                throw new BusinessException(404, "分类不存在");
            }

            path.add(new CategoryPathItemVO(current.getId(),current.getName()));
            current=current.getParentId()==null?null:categoryMapper.selectById(current.getParentId());
        }

        Collections.reverse(path);

        return new CategoryDetailVO(
                category.getId(), category.getName(), category.getLevel(), path
        );
    }

    @Override
    public List<CategoryNavVO> list(Long parentId) {
        if(parentId!=null){
            if(parentId<=0){
                throw new BusinessException(400, "分类ID必须大于0");
            }
            Category parent = categoryMapper.selectById(parentId);
            if(parent==null||hasHiddenCategoryInPath(parent)){
                throw new BusinessException(404, "分类不存在");
            }
        }

        return categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .isNull(parentId==null, Category::getParentId)
                        .eq(parentId != null, Category::getParentId, parentId)
                        .eq(Category::getVisible, 1)
                        .orderByAsc(Category::getSortOrder, Category::getId))
                .stream()
                .map(category -> new CategoryNavVO(
                        category.getId(),
                        category.getName(),
                        category.getLevel(),
                        categoryMapper.selectCount(new LambdaQueryWrapper<Category>()
                                .eq(Category::getParentId, category.getId())
                                .eq(Category::getVisible,1))>0,
                        category.getSortOrder()
                )).toList();
    }

    private boolean hasHiddenCategoryInPath(Category parent) {
        Category current = parent;
        while (current != null) {
            if (!Integer.valueOf(1).equals(current.getVisible())) {
                return true;
            }
            current = current.getParentId() == null
                    ? null
                    : categoryMapper.selectById(current.getParentId());
        }
        return false;
    }

    private CategoryVO toVO(Category category) {
        return new CategoryVO(
                category.getId(), category.getParentId(), category.getLevel(),
                category.getName(), category.getVisible(), category.getSortOrder()
        );
    }
}
