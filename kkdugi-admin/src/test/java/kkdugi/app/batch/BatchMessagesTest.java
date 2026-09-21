package kkdugi.app.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.core.util.MessageUtils;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchMessagesTest {

    @Test
    void everyBatchErrorCodeHasAMessage() throws IllegalAccessException {
        for (Field field : BatchErrors.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String code = (String) field.get(null);
                // useCodeAsDefaultMessage=true라 메시지가 없으면 코드 문자열이 그대로 돌아온다.
                assertThat(MessageUtils.getMessage(code)).as(code).isNotEqualTo(code);
            }
        }
    }
}
