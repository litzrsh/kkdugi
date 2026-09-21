package kkdugi.app.batch.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import kkdugi.KkdugiAdminApplication;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchStrictJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    private void rejects(String json) {
        assertThatThrownBy(() -> objectMapper.readValue(json, BatchRunnerRequest.class)).as(json)
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void capacityMustBeAJsonInteger() {
        assertThat(objectMapper.readValue("{\"capacity\":2}", BatchRunnerRequest.class).getCapacity()).isEqualTo(2);
        for (String bad : List.of("1.9", "1.0", "\"1\"", "true", "[1]", "{}", "1e2", "99999999999")) {
            rejects("{\"capacity\":" + bad + "}");
        }
    }

    @Test
    void textFieldsMustBeJsonStrings() {
        assertThat(objectMapper.readValue("{\"code\":\"c\",\"name\":\"n\",\"status\":\"ACTIVE\"}", BatchRunnerRequest.class).getName())
                .isEqualTo("n");
        for (String field : List.of("code", "name", "status")) {
            for (String bad : List.of("1", "true", "{}", "[]")) {
                rejects("{\"" + field + "\":" + bad + "}");
            }
        }
    }

    @Test
    void nullsStayNullSoServiceValidationReportsThem() {
        BatchRunnerRequest request = objectMapper.readValue("{\"code\":null,\"name\":null,\"capacity\":null}", BatchRunnerRequest.class);
        assertThat(request.getCode()).isNull();
        assertThat(request.getCapacity()).isNull();
    }

    @Test
    void unknownFieldsStillFail() {
        rejects("{\"code\":\"c\",\"extra\":1}");
    }
}
