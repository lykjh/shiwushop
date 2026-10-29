package com.shiwushop.category;

import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryNavigationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private CategoryMapper categoryMapper;

    @Test
    void listsVisibleRootsAndDirectChildrenWithHasChildren() throws Exception {
        Category root = insert("数码", null, 1, 1);
        Category child = insert("手机", root.getId(), 2, 1);
        insert("智能手机", child.getId(), 3, 1);
        Category hiddenChild = insert("隐藏子分类", root.getId(), 2, 0);
        Category rootWithOnlyHiddenChild = insert("服饰", null, 1, 1);
        insert("隐藏上衣", rootWithOnlyHiddenChild.getId(), 2, 0);
        Category hiddenRoot = insert("隐藏一级", null, 1, 0);

        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + root.getId() + ")].hasChildren").value(hasItem(true)))
                .andExpect(jsonPath("$.data[?(@.id == " + rootWithOnlyHiddenChild.getId() + ")].hasChildren")
                        .value(hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.id == " + hiddenRoot.getId() + ")]").isEmpty());

        mockMvc.perform(get("/categories").param("parentId", root.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + child.getId() + ")].hasChildren").value(hasItem(true)))
                .andExpect(jsonPath("$.data[?(@.id == " + hiddenChild.getId() + ")]").isEmpty());
    }

    @Test
    void rejectsHiddenPathMissingParentAndInvalidId() throws Exception {
        Category hiddenRoot = insert("隐藏数码", null, 1, 0);
        Category visibleChild = insert("手机", hiddenRoot.getId(), 2, 1);

        mockMvc.perform(get("/categories").param("parentId", hiddenRoot.getId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/categories").param("parentId", visibleChild.getId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/categories").param("parentId", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/categories").param("parentId", "-1"))
                .andExpect(status().isBadRequest());
    }

    private Category insert(String prefix, Long parentId, int level, int visible) {
        Category category = new Category();
        category.setName(prefix + "-" + UUID.randomUUID());
        category.setParentId(parentId);
        category.setLevel(level);
        category.setVisible(visible);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }
}
