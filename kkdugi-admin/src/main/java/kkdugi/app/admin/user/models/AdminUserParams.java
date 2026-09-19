package kkdugi.app.admin.user.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminUserParams extends BaseParams {

    private String username;
    private String name;
    private String status;

    /** 요청 본문 역직렬화용 — 다중 인자 생성자가 속성 생성자로 감지되어 page/pageSize 생략이 실패하는 것을 막는다(AdminAuthorityParams와 동일). */
    @JsonCreator
    public AdminUserParams() {
    }

    public AdminUserParams(String username, String name, String status, int page, int pageSize) {
        this.username = username;
        this.name = name;
        this.status = status;
        setPage(page);
        setPageSize(pageSize);
    }
}
