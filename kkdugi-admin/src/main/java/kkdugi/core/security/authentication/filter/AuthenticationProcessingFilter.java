package kkdugi.core.security.authentication.filter;

import java.io.IOException;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.exceptions.RestfulAuthenticationException;
import kkdugi.core.exceptions.RestfulException;
import kkdugi.core.security.authentication.AuthTokenCookie;
import kkdugi.core.security.models.Session;
import kkdugi.core.security.models.SessionUser;
import kkdugi.core.security.service.JwtTokenService;
import kkdugi.core.security.service.KkdugiUserDetailsService;
import kkdugi.core.security.service.SessionService;

/**
 * 로그인을 컨트롤러가 아니라 필터로 구현한다(명시적 요구사항) — 로그인 화면의
 * {@code application/x-www-form-urlencoded} form submit({@code username},
 * {@code password}, {@code force})을 {@code attemptAuthentication}에서 읽어
 * {@link AuthenticationManager}에 위임한다.
 *
 * <p>프런트는 form 데이터를 비동기로 제출한다. 성공하면 토큰 쿠키를 설정하고
 * {@code /}로 리다이렉트하며, 실패하면 {@code 401}과 JSON 오류를 반환한다.
 * 중복 로그인은 {@code session.err.duplicate} 코드로 알린다. 프런트는 현재
 * 화면에서 확인 팝업을 열고, 동의한 경우 같은 입력을 {@code force=true}로
 * 재제출한다. 비밀번호는 URL이나 서버 측 확인 상태에 저장하지 않는다.</p>
 *
 * <p>세션 생성({@link SessionService#createSession})을
 * {@code successfulAuthentication}이 아니라 {@code attemptAuthentication}
 * 안에서 하는 이유: {@code AbstractAuthenticationProcessingFilter.doFilter}는
 * {@code attemptAuthentication}만 try/catch로 감싸 실패 시
 * {@code unsuccessfulAuthentication}으로 보내고, {@code successfulAuthentication}
 * 호출은 그 바깥에 있어 거기서 던진 예외는 이 필터가 직접 처리할 수 없다.
 * "이미 세션이 있으니 확인 후 재시도하라"({@link SessionService#ERR_DUPLICATE})는
 * 자격증명 실패와 다른 오류 코드로 알려야 해서, 세션 생성까지 마치고 나서야
 * 인증을 완료시킨다.</p>
 */
public class AuthenticationProcessingFilter extends AbstractAuthenticationProcessingFilter {

    public static final String LOGIN_URL = "/api/v1.0/auth/login";

    public static final String ERR_MALFORMED_REQUEST = "auth.err.malformed_request";

    private static final String SUCCESS_PAGE = "/";
    private static final String SESSION_ATTRIBUTE = AuthenticationProcessingFilter.class.getName() + ".SESSION";

    private final SessionService sessionService;
    private final JwtTokenService jwtTokenService;
    private final KkdugiUserDetailsService userDetailsService;
    private final AuthTokenCookie tokenCookie;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthenticationProcessingFilter(AuthenticationManager authenticationManager, SessionService sessionService, JwtTokenService jwtTokenService, KkdugiUserDetailsService userDetailsService, AuthTokenCookie tokenCookie) {
        super(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGIN_URL), authenticationManager);
        this.sessionService = sessionService;
        this.jwtTokenService = jwtTokenService;
        this.userDetailsService = userDetailsService;
        this.tokenCookie = tokenCookie;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        if (!isFormRequest(request)) {
            throw new RestfulAuthenticationException(ERR_MALFORMED_REQUEST);
        }
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new RestfulAuthenticationException(ERR_MALFORMED_REQUEST);
        }
        boolean force = Boolean.parseBoolean(request.getParameter("force"));

        UsernamePasswordAuthenticationToken authRequest =
                UsernamePasswordAuthenticationToken.unauthenticated(username, password);
        authRequest.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        Authentication result = getAuthenticationManager().authenticate(authRequest);

        SessionUser user = (SessionUser) result.getPrincipal();
        Session session = sessionService.createSession(user, force);
        userDetailsService.recordSuccessfulLogin(user.getId());
        request.setAttribute(SESSION_ATTRIBUTE, session);

        return result;
    }

    private boolean isFormRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED);
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Authentication authResult) throws IOException {
        Session session = (Session) request.getAttribute(SESSION_ATTRIBUTE);
        tokenCookie.write(request, response, jwtTokenService.issue(session.getId()));
        response.sendRedirect(request.getContextPath() + SUCCESS_PAGE);
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response, AuthenticationException failed) throws IOException {
        SecurityContextHolder.clearContext();

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        if (failed instanceof RestfulAuthenticationException restfulEx) {
            response.getOutputStream().write(objectMapper.writeValueAsBytes(restfulEx.getExceptionMessage()));
        } else {
            response.getOutputStream().write(objectMapper.writeValueAsBytes(new ExceptionMessage(RestfulException.ERR_DEFAULT)));
        }
    }
}
