package kkdugi.core.security.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 로그인 요청 바디. {@code force=true}는 이미 활성 세션이 있어도(=
 * {@link kkdugi.core.security.service.SessionService}가
 * {@code session.err.duplicate}로 거부한 뒤) 기존 세션을 끊고 새로 로그인하겠다는
 * 사용자 확인 응답이다 — 클라이언트가 첫 로그인 시도에서 409를 받으면
 * "기존 세션을 종료할까요?"를 물어보고, 동의하면 이 값만 true로 바꿔 같은
 * 요청을 재전송한다.
 */
@Getter
@AllArgsConstructor
public class LoginRequest {

    private final String username;
    private final String password;
    private final boolean force;
}
