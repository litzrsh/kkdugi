package kkdugi.app.batch.models;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** JSON 문자열 토큰만 받는다(숫자·불리언·객체는 거절). {@code null}은 이 deserializer까지 오지 않고 그대로 null이다. */
public class BatchStrictString extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }
        return p.getString();
    }
}
