package com.shiwushop.category;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.service.RegisterService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private RegisterService userService;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void movesBranchToRootAndBack() throws Exception {
        MockHttpSession admin = sessionForRole("ADMIN");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Category root = insert("数码-" + suffix, null, 1);
        Category branch = insert("手机-" + suffix, root.getId(), 2);
        Category leaf = insert("智能手机-" + suffix, branch.getId(), 3);

        mockMvc.perform(put("/admin/categories/{id}", branch.getId())
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", branch.getName(), "visible", 1, "sortOrder", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value(1));

        assertNull(categoryMapper.selectById(branch.getId()).getParentId());
        assertEquals(2, categoryMapper.selectById(leaf.getId()).getLevel());
        assertEquals(1L, searchEvents(branch.getId()));

        mockMvc.perform(put("/admin/categories/{id}", branch.getId())
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", branch.getName(), "parentId", root.getId(),
                                "visible", 1, "sortOrder", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value(2));

        assertEquals(root.getId(), categoryMapper.selectById(branch.getId()).getParentId());
        assertEquals(3, categoryMapper.selectById(leaf.getId()).getLevel());
        assertEquals(2L, searchEvents(branch.getId()));
    }

    @Test
    void rejectsCycleExcessDepthDuplicateAndInvalidInput() throws Exception {
        MockHttpSession admin = sessionForRole("ADMIN");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Category root = insert("数码-" + suffix, null, 1);
        Category child = insert("手机-" + suffix, root.getId(), 2);
        insert("智能手机-" + suffix, child.getId(), 3);
        Category otherRoot = insert("服饰-" + suffix, null, 1);
        Category otherChild = insert("上衣-" + suffix, otherRoot.getId(), 2);

        mockMvc.perform(put("/admin/categories/{id}", root.getId())
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", root.getName(), "parentId", child.getId(),
                                "visible", 1, "sortOrder", 0))))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/admin/categories/{id}", root.getId())
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", root.getName(), "parentId", otherChild.getId(),
                                "visible", 1, "sortOrder", 0))))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/admin/categories/{id}", otherRoot.getId())
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", root.getName(), "visible", 1, "sortOrder", 0))))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/admin/categories/{id}", root.getId())
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", root.getName(), "visible", 2, "sortOrder", 0))))
                .andExpect(status().isBadRequest());

        assertNull(categoryMapper.selectById(root.getId()).getParentId());
        assertEquals(1, categoryMapper.selectById(root.getId()).getLevel());
        assertEquals(otherRoot.getName(), categoryMapper.selectById(otherRoot.getId()).getName());
        assertEquals(0L, searchEvents(root.getId()));
    }

    @Test
    void updateRequiresAdmin() throws Exception {
        Category category = insert("权限-" + UUID.randomUUID(), null, 1);
        String body = om.writeValueAsString(Map.of(
                "name", category.getName(), "visible", 1, "sortOrder", 0));

        mockMvc.perform(put("/admin/categories/{id}", category.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/categories/{id}", category.getId())
                        .session(sessionForRole("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    private Category insert(String name, Long parentId, int level) {
        Category category = new Category();
        category.setName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }

    private Long searchEvents(Long categoryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='SEARCH_CATEGORY_REFRESH' AND aggregate_id=?", Long.class, categoryId);
    }

    private MockHttpSession sessionForRole(String role) {
        String username = "role-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        jdbc.update("UPDATE user_accounts SET role=? WHERE user_id=?", role, userId);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
