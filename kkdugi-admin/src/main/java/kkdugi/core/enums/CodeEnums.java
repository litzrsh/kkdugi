package kkdugi.core.enums;

import kkdugi.core.util.MessageUtils;

public interface CodeEnums {
    
    String getCode();

    String getLabelCode();

    default String getLabel() {
        return MessageUtils.getMessage(getLabelCode());
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
