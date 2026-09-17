package kkdugi.api.admin.code;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import kkdugi.app.admin.code.models.CodeContent;
import kkdugi.app.admin.code.models.CodeLocale;
import kkdugi.app.admin.code.models.CodePersistRequest;
import kkdugi.app.admin.code.models.CodeSearchParams;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class CodeAdminControllerTest {

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
        String body = objectMapper.writeValueAsString(new CodePersistRequest(
                List.of(new CodeContent(null, null, "test_api_code", null, "Y",
                        null, null, null, null, null, null, null, null)),
                null, null));

        mockMvc.perform(post("/api/v1.0/admin/code/persist")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void persist_returns409_whenUpdatingMissingId() throws Exception {
        String body = objectMapper.writeValueAsString(new CodePersistRequest(null,
                List.of(new CodeContent("C_MISSING", null, null,
                        Map.of("ko_KR", new CodeLocale("값", null)),
                        null, null, null, null, null, null, null, null, null)),
                null));

        mockMvc.perform(post("/api/v1.0/admin/code/persist")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void search_returnsPagedContents() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CodeSearchParams(null, null, null, null, null, 1, 10));

        mockMvc.perform(post("/api/v1.0/admin/code")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1));
    }
}
