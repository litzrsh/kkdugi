package kkdugi.app.admin.user.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * 사용자 등록/비밀번호 초기화 때 쓰는 임시 비밀번호 생성기. 화면에서 읽기 쉽도록 혼동되는
 * 문자(0/O/o, 1/l/I)를 뺀 영문 대소문자+숫자로 12자를 만들고, 세 문자 종류가 모두 들어가도록 보장한다.
 */
@Component
public class TemporaryPasswordGenerator {

    static final int LENGTH = 12;

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String ALL = UPPER + LOWER + DIGIT;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] chars = new char[LENGTH];
        chars[0] = pick(UPPER);
        chars[1] = pick(LOWER);
        chars[2] = pick(DIGIT);
        for (int i = 3; i < LENGTH; i++) {
            chars[i] = pick(ALL);
        }
        // 앞 세 자리가 종류별로 고정돼 있으므로 Fisher-Yates로 섞는다.
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char swap = chars[i];
            chars[i] = chars[j];
            chars[j] = swap;
        }
        return new String(chars);
    }

    private char pick(String source) {
        return source.charAt(random.nextInt(source.length()));
    }
}
