package com.shiwushop.category;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.service.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryHierarchyTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryMapper categoryMapper;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void createsThreeLevels() throws Exception {
        MockHttpSession admin = adminSession();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        long root = create(admin, Map.of("name", "数码-" + suffix));
        long child = create(admin, Map.of(
                "name", "手机-" + suffix, "parentId", root));
        long third = create(admin, Map.of(
                "name", "智能手机-" + suffix, "parentId", child));

        assertEquals(1, categoryMapper.selectById(root).getLevel());
        assertEquals(2, categoryMapper.selectById(child).getLevel());
        assertEquals(3, categoryMapper.selectById(third).getLevel());
        for (long id : new long[]{root, child, third}) {
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='SEARCH_CATEGORY_REFRESH' AND aggregate_id=?", Long.class, id));
        }

        mockMvc.perform(get("/categories").param("parentId", String.valueOf(root)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("手机-" + suffix));
    }

    @Test
    void rejectsFourthLevelAndDuplicateSibling() throws Exception {
        MockHttpSession admin = adminSession();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        long root = create(admin, Map.of("name", "数码-" + suffix));
        long child = create(admin, Map.of(
                "name", "手机-" + suffix, "parentId", root));
        long third = create(admin, Map.of(
                "name", "智能手机-" + suffix, "parentId", child));

        mockMvc.perform(post("/admin/categories").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", "第四级", "parentId", third))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/admin/categories").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", "手机-" + suffix, "parentId", root))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/admin/categories").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", "错误", "visible", 2))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hiddenParentCannotBeBrowsed() throws Exception {
        MockHttpSession admin = adminSession();
        long hidden = create(admin, Map.of(
                "name", "隐藏-" + UUID.randomUUID(), "visible", 0));
        create(admin, Map.of("name", "子分类", "parentId", hidden));

        mockMvc.perform(get("/categories").param("parentId", String.valueOf(hidden)))
                .andExpect(status().isNotFound());
    }

    private long create(MockHttpSession admin, Map<String, ?> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/categories").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();

        return om.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private MockHttpSession adminSession() {
        String username = "admin-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        jdbc.update("UPDATE user_accounts SET role='ADMIN' WHERE user_id=?", userId);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
