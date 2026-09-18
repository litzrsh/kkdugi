package kkdugi.core.security.authentication.filter;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.exceptions.RestfulAuthenticationException;
import kkdugi.core.security.models.LoginRequest;
import kkdugi.core.security.models.LoginResponse;
import kkdugi.core.security.models.Session;
import kkdugi.core.security.models.SessionUser;
import kkdugi.core.security.service.JwtTokenService;
import kkdugi.core.security.service.KkdugiUserDetailsService;
import kkdugi.core.security.service.SessionService;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인을 컨트롤러가 아니라 필터로 구현한다(명시적 요구사항) — JSON 바디를
 * {@code attemptAuthentication}에서 직접 파싱해 {@link AuthenticationManager}에
 * 위임한다.
 *
 * <p>세션 생성({@link SessionService#createSession})을
 * {@code successfulAuthentication}이 아니라 {@code attemptAuthentication}
 * 안에서 하는 이유: {@code AbstractAuthenticationProcessingFilter.doFilter}는
 * {@code attemptAuthentication}만 try/catch로 감싸 실패 시
 * {@code unsuccessfulAuthentication}으로 보내고, {@code successfulAuthentication}
 * 호출은 그 바깥에 있어 거기서 던진 예외는 이 필터가 직접 처리할 수 없다.
 * "이미 세션이 있으니 확인 후 재시도하라"({@link SessionService#ERR_DUPLICATE})는
 * 자격증명 실패와 다른 HTTP 상태(409)로 내려줘야 해서, 세션 생성까지
 * 마치고 나서야 인증을 완료시킨다.</p>
 */
public class AuthenticationProcessingFilter extends AbstractAuthenticationProcessingFilter {

    public static final String LOGIN_URL = "/api/v1.0/admin/auth/login";

    public static final String ERR_BAD_CREDENTIALS = "auth.err.bad_credentials";
    public static final String ERR_MALFORMED_REQUEST = "auth.err.malformed_request";

    private static final String SESSION_ATTRIBUTE = AuthenticationProcessingFilter.class.getName() + ".SESSION";

    private final SessionService sessionService;
    private final JwtTokenService jwtTokenService;
    private final KkdugiUserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    public AuthenticationProcessingFilter(AuthenticationManager authenticationManager, SessionService sessionService,
            JwtTokenService jwtTokenService, KkdugiUserDetailsService userDetailsService, ObjectMapper objectMapper) {
        super(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGIN_URL), authenticationManager);
        this.sessionService = sessionService;
        this.jwtTokenService = jwtTokenService;
        this.userDetailsService = userDetailsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        LoginRequest loginRequest = readLoginRequest(request);

        UsernamePasswordAuthenticationToken authRequest =
                UsernamePasswordAuthenticationToken.unauthenticated(loginRequest.getUsername(), loginRequest.getPassword());
        authRequest.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        Authentication result = getAuthenticationManager().authenticate(authRequest);

        SessionUser user = (SessionUser) result.getPrincipal();
        Session session = sessionService.createSession(user, loginRequest.isForce());
        userDetailsService.recordSuccessfulLogin(user.getId());
        request.setAttribute(SESSION_ATTRIBUTE, session);

        return result;
    }

    private LoginRequest readLoginRequest(HttpServletRequest request) {
        LoginRequest loginRequest;
        try {
            loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
        } catch (IOException | RuntimeException e) {
            throw new RestfulAuthenticationException(ERR_MALFORMED_REQUEST);
        }
        if (!StringUtils.hasText(loginRequest.getUsername()) || !StringUtils.hasText(loginRequest.getPassword())) {
            throw new RestfulAuthenticationException(ERR_MALFORMED_REQUEST);
        }
        return loginRequest;
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain, Authentication authResult) throws IOException {
        SecurityContextHolder.getContext().setAuthentication(authResult);

        SessionUser user = (SessionUser) authResult.getPrincipal();
        Session session = (Session) request.getAttribute(SESSION_ATTRIBUTE);
        String token = jwtTokenService.issue(session.getId(), session.getExpiresAt());
        List<String> authorities = user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        writeJson(response, HttpStatus.OK,
                new LoginResponse(token, user.getId(), user.getUsername(), user.getName(), authorities));
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failed) throws IOException {
        SecurityContextHolder.clearContext();

        ExceptionMessage message;
        HttpStatus status;
        if (failed instanceof RestfulAuthenticationException restfulEx) {
            message = restfulEx.getExceptionMessage();
            status = SessionService.ERR_DUPLICATE.equals(message.getCode()) ? HttpStatus.CONFLICT
                    : HttpStatus.UNAUTHORIZED;
        } else {
            message = new ExceptionMessage(ERR_BAD_CREDENTIALS);
            status = HttpStatus.UNAUTHORIZED;
        }
        writeJson(response, status, message);
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Object body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
