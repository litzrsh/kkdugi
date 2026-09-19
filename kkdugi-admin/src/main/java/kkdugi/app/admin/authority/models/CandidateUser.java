package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** 후보 사용자 조회 행 — {@code kkdugi_user_base}에서 화면에 필요한 컬럼만 읽는다(비밀번호 등 제외). */
@Getter
@Setter
public class CandidateUser extends BaseModel {

    private String id;
    private String username;
    private String name;
    private String image;
}
