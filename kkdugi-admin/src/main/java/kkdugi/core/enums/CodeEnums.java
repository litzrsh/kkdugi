package kkdugi.core.enums;

import java.util.ArrayList;
import java.util.List;

import kkdugi.app.code.models.Code;
import kkdugi.core.util.MessageUtils;

public interface CodeEnums {
    
    String getCode();

    String getLabelCode();

    default String getLabel() {
        return MessageUtils.getMessage(getLabelCode());
    }

    /** 선언 순서대로 코드 목록을 만든다. 이름은 현재 요청의 Locale로 해석한다. */
    static List<Code> toCodes(Class<? extends CodeEnums> type) {
        if (type == null || !type.isEnum()) {
            throw new IllegalArgumentException("CodeEnums를 구현한 enum 타입이 필요합니다");
        }
        List<Code> result = new ArrayList<>();
        for (CodeEnums value : type.getEnumConstants()) {
            Code code = new Code();
            code.setId(type.getSimpleName() + "." + value.getCode());
            code.setCode(value.getCode());
            code.setName(value.getLabel());
            code.setPath(type.getSimpleName());
            code.setLevel(0);
            code.setSort(result.size() + 1);
            result.add(code);
        }
        return result;
    }

    /** core/enums 바로 아래의 단순 클래스 이름만 허용한다. */
    static List<Code> toCodes(String enumName) {
        if (enumName == null || !enumName.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("올바른 enum 클래스 이름이 필요합니다");
        }
        try {
            Class<?> type = Class.forName(CodeEnums.class.getPackageName() + "." + enumName,
                    false, CodeEnums.class.getClassLoader());
            if (!type.isEnum() || !CodeEnums.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("CodeEnums를 구현한 enum 타입이 필요합니다");
            }
            return toCodes(type.asSubclass(CodeEnums.class));
        } catch (NoClassDefFoundError e) {
            // Windows는 다른 대소문자의 파일을 찾더라도 JVM에서 클래스 이름 불일치로 거부한다.
            if (e.getMessage() != null && e.getMessage().contains("(wrong name:")) {
                throw new IllegalArgumentException("enum 클래스 이름의 대소문자가 일치하지 않습니다: " + enumName, e);
            }
            throw e;
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("enum 클래스를 찾을 수 없습니다: " + enumName, e);
        }
    }

    static <E extends CodeEnums> E fromCode(Class<E> clazz, String code) {
        E[] constants = clazz.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException(clazz.getName() + "은(는) enum 타입이 아닙니다");
        }
        for (E constant : constants) {
            if (constant.getCode().equals(code)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(
                clazz.getSimpleName() + "에 code=" + code + "인 상수가 없습니다");
    }
}
