package kkdugi.app.batch.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import tools.jackson.databind.ObjectMapper;

/** 요청 헤더·본문에서 멱등 key를 만든다. 본문은 키 순서와 무관하게 정규화해서 hash한다. */
@Component
public class BatchRequestKeys {

    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7e]{1,200}");
    private static final Pattern CREATED_AT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");

    private final ObjectMapper objectMapper;

    public BatchRequestKeys(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BatchIdempotencyKey forUser(String userId, String method, String path, String keyHeader,
            String createdAtHeader, Object body, String ifMatch) {
        if (keyHeader == null || !KEY.matcher(keyHeader).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        if (createdAtHeader == null || !CREATED_AT.matcher(createdAtHeader).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(createdAtHeader);
        } catch (DateTimeParseException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        BatchIdempotencyKey key = new BatchIdempotencyKey();
        key.setSubjectType(IdempotencySubjectType.USER);
        key.setSubjectId(userId);
        key.setOperationHash(BatchTokenService.sha256Hex(method + " " + path));
        key.setRequestKey(keyHeader);
        key.setRequestHash(BatchTokenService.sha256Hex(
                canonical(body) + "\n" + (ifMatch == null ? "" : ifMatch) + "\n" + createdAtHeader));
        key.setCreatedAt(createdAt);
        return key;
    }

    String canonical(Object body) {
        StringBuilder out = new StringBuilder();
        append(out, body == null ? null : objectMapper.convertValue(body, Object.class));
        return out.toString();
    }

    private void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(objectMapper.writeValueAsString(entry.getKey())).append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value instanceof CharSequence text) {
            out.append(objectMapper.writeValueAsString(text.toString()));
        } else {
            out.append(value);
        }
    }
}
