package com.shiwushop.category;

import com.shiwushop.entity.category.Category;
import com.shiwushop.mapper.category.CategoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryDetailTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private CategoryMapper categoryMapper;

    @Test
    void returnsPathFromRootToThirdLevel() throws Exception {
        Category root = insert("数码", null, 1, 1);
        Category child = insert("手机", root.getId(), 2, 1);
        Category leaf = insert("智能手机", child.getId(), 3, 1);

        mockMvc.perform(get("/categories/{id}", leaf.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(leaf.getId()))
                .andExpect(jsonPath("$.data.level").value(3))
                .andExpect(jsonPath("$.data.path.length()").value(3))
                .andExpect(jsonPath("$.data.path[0].id").value(root.getId()))
                .andExpect(jsonPath("$.data.path[1].id").value(child.getId()))
                .andExpect(jsonPath("$.data.path[2].id").value(leaf.getId()));

        mockMvc.perform(get("/categories/{id}", root.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path.length()").value(1));
    }

    @Test
    void hidesCategoryWhenItOrAnAncestorIsHidden() throws Exception {
        Category root = insert("隐藏数码", null, 1, 0);
        Category child = insert("手机", root.getId(), 2, 1);
        Category hiddenLeaf = insert("隐藏手机", child.getId(), 3, 0);

        mockMvc.perform(get("/categories/{id}", child.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/categories/{id}", hiddenLeaf.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/categories/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
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
