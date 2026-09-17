package kkdugi.core.security.service;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.core.exceptions.RestfulAuthenticationException;
import kkdugi.core.security.config.SecurityConfigurationProperties;
import kkdugi.core.security.mapper.SessionMapper;
import kkdugi.core.security.models.Session;
import kkdugi.core.security.models.SessionUser;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.DateUtils;
import kkdugi.core.util.SerialUtils;

@Service
public class SessionService {

    public static final String ERR_NOT_EXISTS = "session.err.not_exists";
    public static final String ERR_DUPLICATE = "session.err.duplicate";

    private static final SerialConfig SERIAL_CONFIG = new SerialConfig() {

        @Override
        public String getId() {
            return "KKDUGI_SESSION";
        }

        @Override
        public Duration getDuration() {
            return Duration.ofSeconds(1);
        }

        @Override
        public DateFormat getDateFormat() {
            return new SimpleDateFormat("yyyyMMddHHmmss");
        }

        @Override
        public String getValueFormatter() {
            return "S%s%04d";
        }
    };

    private final SecurityConfigurationProperties properties;
    private final SessionMapper sessionMapper;

    public SessionService(SecurityConfigurationProperties properties, SessionMapper sessionMapper) {
        this.properties = properties;
        this.sessionMapper = sessionMapper;
    }

    /**
     * 로그인 성공 시 세션을 새로 만든다. {@code allowMultiple=false}인데
     * 해당 사용자의 만료되지 않은 세션이 이미 있으면:
     * <ul>
     *   <li>{@code force=false}면 {@link #ERR_DUPLICATE}로 거부한다 —
     *   로그인 필터(Phase 4)가 이 예외를 받아 클라이언트에게 "기존 세션을
     *   종료하고 계속하시겠습니까?"를 물어볼 수 있는 응답을 내려준다.</li>
     *   <li>{@code force=true}면(사용자가 위 질문에 동의한 재시도) 기존
     *   세션을 끊고 새 세션을 만든다.</li>
     * </ul>
     * {@code allowMultiple=true}면 force 여부와 무관하게 기존 세션을 그대로
     * 둔 채 새 세션을 추가로 만든다.
     *
     * <p>{@code allowMultiple=false}일 때 이전 세션을 끊는 경우(force 또는
     * 만료 정리)는 {@code findByUserId}가 돌려준 세션 하나만
     * {@code deleteById}로 지우지 않고, {@code deleteByUserId}로 그 사용자의
     * 세션을 전부 지운다. {@code findByUserId}는 가장 최근 것 하나만 반환하므로,
     * (예: allowMultiple 설정을 바꾼 적이 있거나 경합으로) 그 사용자에게
     * 남아 있는 세션 행이 두 개 이상이면 deleteById로는 나머지가 유효한
     * 세션으로 남는다 — allowMultiple=false 정책상 한 사용자에 세션은
     * 최대 하나여야 하므로 사용자 ID 기준으로 확실히 정리한다.</p>
     */
    @Transactional
    public Session createSession(SessionUser user, boolean force) {
        Optional<Session> existing = sessionMapper.findByUserId(user.getId());
        if (existing.isPresent()) {
            Session existingSession = existing.get();
            if (properties.isAllowMultiple()) {
                if (existingSession.getExpiresAt().before(new Date())) {
                    sessionMapper.deleteById(existingSession.getId());
                }
            } else {
                if (existingSession.getExpiresAt().after(new Date()) && !force) {
                    throw new RestfulAuthenticationException(user, ERR_DUPLICATE);
                }
                sessionMapper.deleteByUserId(user.getId());
            }
        }

        Date now = new Date();
        Session session = new Session();
        session.setId(SerialUtils.next(SERIAL_CONFIG));
        session.setUserId(user.getId());
        session.setDetails(user);
        session.setCreatedAt(now);
        session.setExpiresAt(DateUtils.plus(now, properties.getSessionTimeout()));
        session.setUpdatedAt(now);
        sessionMapper.save(session);
        return session;
    }

    public Session findSession(String id) {
        Session session = sessionMapper.findById(id).orElse(null);
        if (session == null || session.getExpiresAt().before(new Date())) {
            return null;
        }
        return session;
    }

    @Transactional
    public void invalidate(String id) {
        sessionMapper.deleteById(id);
    }
}
