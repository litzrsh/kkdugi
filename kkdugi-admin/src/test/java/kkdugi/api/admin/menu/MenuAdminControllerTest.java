package kkdugi.api.admin.menu;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.menu.models.MenuContent;
import kkdugi.app.admin.menu.models.MenuLocale;
import kkdugi.app.admin.menu.models.MenuPersistRequest;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class MenuAdminControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void persist_returns400_whenLocaleMissing() throws Exception {
        String body = objectMapper.writeValueAsString(new MenuPersistRequest(
                List.of(new MenuContent(null, null, null, null, "adcode", "Y", "Y", null, null, 1)),
                null, null));

        mockMvc.perform(post("/api/v1.0/admin/menu/persist")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void persist_returns409_whenUpdatingMissingId() throws Exception {
        String body = objectMapper.writeValueAsString(new MenuPersistRequest(null,
                List.of(new MenuContent("M_MISSING", null, Map.of("ko_KR", new MenuLocale("값", null)),
                        null, "adcode", "Y", "Y", null, null, 1)),
                null));

        mockMvc.perform(post("/api/v1.0/admin/menu/persist")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void search_returnsTree() throws Exception {
        mockMvc.perform(get("/api/v1.0/admin/menu"))
                .andExpect(status().isOk());
    }
}
