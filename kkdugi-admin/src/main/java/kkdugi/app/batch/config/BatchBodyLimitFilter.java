package kkdugi.app.batch.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.exceptions.BatchErrors;

/**
 * 배치 API 요청 본문을 {@code limitBytes}(UTF-8 byte) 이하로 제한한다(계약: 1 MiB 초과는 413). Content-Length를 믿지 않는다 —
 * 선언이 한도를 넘으면 읽기 전에 거절하지만, 선언이 없거나(chunked) 거짓이어도 실제로 읽은 byte 수로 판정한다.
 * 한도 이하의 본문은 메모리에 담아 하위 필터·컨트롤러가 다시 읽을 수 있게 한다. 한도 이상을 메모리에 올리지 않는다.
 * <b>인증 이후</b>에 실행되도록 Spring Security 필터 체인 뒤에 등록한다({@link BatchBodyLimitConfig}).
 */
public class BatchBodyLimitFilter extends OncePerRequestFilter {

    private final int limitBytes;
    private final BatchErrorWriter errors;

    public BatchBodyLimitFilter(int limitBytes, BatchErrorWriter errors) {
        this.limitBytes = limitBytes;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > limitBytes) {
            errors.write(response, 413, BatchErrors.PAYLOAD_TOO_LARGE);
            return;
        }
        byte[] body = readAtMost(request.getInputStream());
        if (body == null) {
            errors.write(response, 413, BatchErrors.PAYLOAD_TOO_LARGE);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** 한도를 넘는 순간 읽기를 멈추고 null을 돌려준다. */
    private byte[] readAtMost(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limitBytes) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("non-blocking reads are not supported");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return in.read(target, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            String encoding = getCharacterEncoding();
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (IllegalArgumentException e) {
                    // 알 수 없는 인코딩 이름은 UTF-8로 처리한다.
                }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
