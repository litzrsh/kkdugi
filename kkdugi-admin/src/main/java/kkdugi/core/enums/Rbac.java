package kkdugi.core.enums;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Rbac implements CodeEnums {

    READ("10", "rbac.10", 0x01), // 조회
    WRTE("20", "rbac.20", 0x02), // 등록
    DELT("30", "rbac.30", 0x04), // 삭제
    EXEC("40", "rbac.40", 0x08); // 실행
    
    private final String code;
    private final String labelCode;
    private final int value;

    public static final Rbac fromCode(String code) {
        return CodeEnums.fromCode(Rbac.class, code);
    }

    public static Map<String, Boolean> toMap(int authority) {
        Map<String, Boolean> rbacMap = new HashMap<>();
        for (Rbac val : Rbac.values()) {
            boolean isMatch = ((authority & val.getValue()) == val.getValue());
            rbacMap.put(val.getCode(), isMatch);
        }
        return rbacMap;
    }

    public static int fromMap(Map<String, Boolean> map) {
        int authority = 0;
        for (Map.Entry<String, Boolean> entry : map.entrySet()) {
            Rbac rbac = Rbac.fromCode(entry.getKey());
            if (rbac == null || !entry.getValue()) {
                continue;
            }
            authority |= rbac.getValue();
        }
        return authority;
    }
}
