package kkdugi.web.admin.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 사용자관리 화면의 상태 필터/배지가 쓰는 서버 등록 상태값 하나. */
@Getter
@AllArgsConstructor
public class StatusOption {

    private final String code;
    private final String label;
}
