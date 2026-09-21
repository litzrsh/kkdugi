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
class BatchAgentJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    private void rejects(String json, Class<?> type) {
        assertThatThrownBy(() -> objectMapper.readValue(json, type)).as(json).isInstanceOf(JacksonException.class);
    }

    @Test
    void sessionRequestRequiresStringTokens() {
        rejects("{\"expectedSession\":1}", BatchSessionRequest.class);
        rejects("{\"bootId\":123}", BatchSessionRequest.class);
        rejects("{\"agentVersion\":true}", BatchSessionRequest.class);
        BatchSessionRequest ok = objectMapper.readValue(
                "{\"bootId\":\"b\",\"expectedSession\":\"0\",\"agentVersion\":\"v\"}", BatchSessionRequest.class);
        assertThat(ok.getExpectedSession()).isEqualTo("0");
    }

    @Test
    void heartbeatRequiresAnIntegerFreeSlotsAndStringFields() {
        for (String bad : List.of("\"1\"", "1.5", "true")) {
            rejects("{\"freeSlots\":" + bad + "}", BatchHeartbeatRequest.class);
        }
        rejects("{\"mode\":1}", BatchHeartbeatRequest.class);
        rejects("{\"observedAt\":20260920}", BatchHeartbeatRequest.class);
        rejects("{\"assignments\":[{\"id\":5}]}", BatchHeartbeatRequest.class);
        rejects("{\"assignments\":[{\"phase\":true}]}", BatchHeartbeatRequest.class);
        assertThat(objectMapper.readValue("{\"freeSlots\":2}", BatchHeartbeatRequest.class).getFreeSlots()).isEqualTo(2);
    }

    @Test
    void registrationAndRevokeRequestsRequireStrings() {
        for (String field : List.of("runnerCode", "agentVersion", "hostname", "os", "architecture")) {
            rejects("{\"" + field + "\":5}", BatchRegistrationRequest.class);
        }
        rejects("{\"reason\":5}", BatchRevokeRequest.class);
    }

    @Test
    void unknownFieldsFail() {
        rejects("{\"bootId\":\"b\",\"extra\":1}", BatchSessionRequest.class);
    }
}
