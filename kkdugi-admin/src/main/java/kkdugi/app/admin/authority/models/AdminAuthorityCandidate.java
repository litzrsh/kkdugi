package kkdugi.app.admin.authority.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 권한에 붙일 수 있는 후보 사용자. */
@Getter
@AllArgsConstructor
public class AdminAuthorityCandidate {

    private final String id;
    private final String username;
    private final String name;
    private final String image;
}
