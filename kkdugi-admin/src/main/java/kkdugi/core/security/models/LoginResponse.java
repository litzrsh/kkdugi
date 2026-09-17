package kkdugi.core.security.models;

import java.util.List;

/**
 * 로그인 성공 응답. {@link SessionUser}를 그대로 내려주지 않는다 —
 * SessionUser는 세션 스냅샷 저장용 내부 모델이라 password 등도 필드로
 * 갖고 있어(SessionUser 클래스 주석 참고), API 응답 전용으로 필요한
 * 필드만 따로 담는다.
 */
public record LoginResponse(String token, String userId, String username, String name, List<String> authorities) {
}
