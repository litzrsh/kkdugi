package kkdugi.api.admin;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.i18n.models.AdminMessage;
import kkdugi.app.admin.i18n.models.AdminMessagePersistRequest;
import kkdugi.app.admin.i18n.models.AdminMessageParams;
import kkdugi.app.admin.i18n.mapper.AdminMessageMapper;
import kkdugi.core.i18n.service.KkdugiMessageSource;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminMessageControllerTest {

    private MockMvc mockMvc;

        @Autowired
        private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminMessageMapper mapper;

    @Autowired
    private KkdugiMessageSource messageSource;

        @BeforeEach
        void setUp() {
                mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }

    @AfterEach
    void cleanUp() {
        mapper.deleteByCode("test.api.batch");
        messageSource.refresh("test.api.batch", "ko_KR");
    }

    @Test
    void persist_insertsCode_andReturns200() throws Exception {
        String body = objectMapper.writeValueAsString(new AdminMessagePersistRequest(
                List.of(new AdminMessage("test.api.batch", Map.of("ko_KR", "API 테스트 값"))),
                null, null));

        mockMvc.perform(post("/api/v1.0/admin/i18n/persist")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void persist_returns400_whenMessageCodeInvalid() throws Exception {
        String body = objectMapper.writeValueAsString(new AdminMessagePersistRequest(
                List.of(new AdminMessage("Invalid.Code", Map.of("ko_KR", "값"))),
                null, null));

        mockMvc.perform(post("/api/v1.0/admin/i18n/persist")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("message.err.invalid_format"));
    }

    @Test
    void persist_returns409_whenUpdatingMissingCode() throws Exception {
        String body = objectMapper.writeValueAsString(new AdminMessagePersistRequest(
                null,
                List.of(new AdminMessage("test.api.missing", Map.of("ko_KR", "값"))),
                null));

        mockMvc.perform(post("/api/v1.0/admin/i18n/persist")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void search_returnsPagedContents() throws Exception {
        String body = objectMapper.writeValueAsString(new AdminMessageParams(null, null, 1, 10));

        mockMvc.perform(post("/api/v1.0/admin/i18n")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1));
    }
}
