package com.shiwushop.category;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminCategoryListTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CategoryMapper categoryMapper;

    @Test
    void adminSeesHiddenCategoriesAtEveryLevel() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String rootName = "隐藏一级-" + suffix;
        String childName = "隐藏分类下的二级-" + suffix;

        Category root = new Category();
        root.setName(rootName);
        root.setLevel(1);
        root.setVisible(0);
        root.setSortOrder(0);
        categoryMapper.insert(root);

        Category child = new Category();
        child.setParentId(root.getId());
        child.setName(childName);
        child.setLevel(2);
        child.setVisible(1);
        child.setSortOrder(0);
        categoryMapper.insert(child);

        mockMvc.perform(get("/admin/categories").session(sessionForRole("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", hasItems(rootName, childName)));

        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(rootName)));
    }

    @Test
    void adminListRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/admin/categories"))
                .andExpect(status().isUnauthorized());

        MockHttpSession stale = new MockHttpSession();
        stale.setAttribute("userId", Long.MAX_VALUE);
        mockMvc.perform(get("/admin/categories").session(stale))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/categories").session(sessionForRole("USER")))
                .andExpect(status().isForbidden());

        MockHttpSession disabled = sessionForRole("ADMIN");
        jdbcTemplate.update("UPDATE user_accounts SET status = 'DISABLED' WHERE user_id = ?",
                disabled.getAttribute("userId"));
        mockMvc.perform(get("/admin/categories").session(disabled))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpSession sessionForRole(String role) {
        String username = "role_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
