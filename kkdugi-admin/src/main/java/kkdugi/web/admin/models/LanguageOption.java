package kkdugi.web.admin.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 관리자 셸(admin/index)이 표시할 수 있는 시스템 등록 언어 하나. */
@Getter
@AllArgsConstructor
public class LanguageOption {

    private final String code;
    private final String label;
}
