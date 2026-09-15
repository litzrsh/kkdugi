package kkdugi.api.admin.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.i18n.CrudType;
import kkdugi.core.i18n.I18nMessageMapper;
import kkdugi.core.i18n.KkdugiMessageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = KkdugiAdminApplication.class)
@AutoConfigureMockMvc
class MessageAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private I18nMessageMapper mapper;

    @Autowired
    private KkdugiMessageSource messageSource;

    @AfterEach
    void cleanUp() {
        mapper.delete("test.api.batch", "ko_KR");
        messageSource.refresh("test.api.batch", "ko_KR");
    }

    @Test
    void save_insertsRow_andReturns200WithCounts() throws Exception {
        String body = objectMapper.writeValueAsString(new MessageSaveRequest(List.of(
                new MessageRowRequest(CrudType.INSERT, "test.api.batch", "ko_KR", "API 테스트 값")
        )));

        mockMvc.perform(post("/api/admin/i18n/messages")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(1));
    }

    @Test
    void save_returns400_whenMessageCodeInvalid() throws Exception {
        String body = objectMapper.writeValueAsString(new MessageSaveRequest(List.of(
                new MessageRowRequest(CrudType.INSERT, "Invalid.Code", "ko_KR", "값")
        )));

        mockMvc.perform(post("/api/admin/i18n/messages")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowIndex").value(0));
    }

    @Test
    void save_returns409_whenUpdatingMissingRow() throws Exception {
        String body = objectMapper.writeValueAsString(new MessageSaveRequest(List.of(
                new MessageRowRequest(CrudType.UPDATE, "test.api.missing", "ko_KR", "값")
        )));

        mockMvc.perform(post("/api/admin/i18n/messages")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void list_returnsRows() throws Exception {
        mockMvc.perform(get("/api/admin/i18n/messages")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0));
    }
}
