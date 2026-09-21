package kkdugi.app.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import tools.jackson.databind.json.JsonMapper;

class BatchBodyLimitFilterTest {

    private static final int LIMIT = 16;

    private final BatchBodyLimitFilter filter = new BatchBodyLimitFilter(LIMIT, new BatchErrorWriter(JsonMapper.builder().build()));

    /** declared가 null이면 실제 본문 길이를 Content-Length로 쓰고, 아니면 그 값을 선언한 것처럼 보이게 한다(-1은 Content-Length 없음). */
    private MockHttpServletResponse run(byte[] body, Long declared, AtomicReference<byte[]> seen) throws Exception {
        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/api/v1.0/batch-agent/heartbeat");
        mock.setContent(body);
        HttpServletRequest request = declared == null ? mock : new HttpServletRequestWrapper(mock) {
            @Override
            public long getContentLengthLong() {
                return declared;
            }

            @Override
            public int getContentLength() {
                return (int) (long) declared;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> seen.set(req.getInputStream().readAllBytes());
        filter.doFilter(request, response, chain);
        return response;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBodyOfExactlyTheLimitPassesAndTheDownstreamReadsItAgain() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        byte[] body = bytes("{\"a\":\"12345678\"}"); // 16 bytes
        assertThat(body).hasSize(LIMIT);

        MockHttpServletResponse response = run(body, null, seen);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(body);
    }

    @Test
    void oneByteOverTheLimitIsRejectedWithoutCallingDownstream() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        MockHttpServletResponse response = run(bytes("{\"a\":\"123456789\"}"), null, seen); // 17 bytes

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("batch.payload.too_large");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(seen.get()).isNull();
    }

    @Test
    void multiByteUtf8IsCountedInBytesNotCharacters() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        assertThat(run(bytes("가나다라마"), null, seen).getStatus()).isEqualTo(200); // 15 bytes
        seen.set(null);
        assertThat(run(bytes("가나다라마바"), null, seen).getStatus()).isEqualTo(413); // 6자지만 18 bytes
        assertThat(seen.get()).isNull();
    }

    @Test
    void leadingWhitespaceCountsTowardTheLimit() throws Exception {
        assertThat(run(bytes(" ".repeat(LIMIT + 1) + "{}"), null, new AtomicReference<>()).getStatus()).isEqualTo(413);
    }

    @Test
    void theLimitAppliesEvenWithoutAContentLength() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        assertThat(run(new byte[LIMIT], -1L, seen).getStatus()).isEqualTo(200);
        seen.set(null);
        assertThat(run(new byte[LIMIT + 1], -1L, seen).getStatus()).isEqualTo(413);
        assertThat(seen.get()).isNull();
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRejectedBeforeReading() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        assertThat(run(new byte[0], 1000L, seen).getStatus()).isEqualTo(413);
        assertThat(seen.get()).isNull();
    }

    @Test
    void theDownstreamCanReadTheBodyThroughAReaderToo() throws Exception {
        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/x");
        mock.setContent(bytes("{\"k\":\"가\"}"));
        mock.setCharacterEncoding("UTF-8");
        AtomicReference<String> text = new AtomicReference<>();

        filter.doFilter(mock, new MockHttpServletResponse(), (req, res) -> text.set(req.getReader().readLine()));

        assertThat(text.get()).isEqualTo("{\"k\":\"가\"}");
    }
}
