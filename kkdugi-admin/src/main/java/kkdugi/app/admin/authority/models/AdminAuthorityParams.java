package kkdugi.app.admin.authority.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminAuthorityParams extends BaseParams {

    private String role;
    private String type;
    private String name;

    /**
     * 요청 본문 역직렬화용. 아래 다중 인자 생성자가 -parameters 로 인해 속성 생성자로
     * 자동 감지되면 page/pageSize를 생략한 요청이 "null into int"로 실패하므로,
     * 기본 생성자를 명시적 creator 로 지정해 setter 기반 바인딩을 쓰게 한다.
     */
    @JsonCreator
    public AdminAuthorityParams() {
    }

    public AdminAuthorityParams(String role, String type, String name, int page, int pageSize) {
        this.role = role;
        this.type = type;
        this.name = name;
        setPage(page);
        setPageSize(pageSize);
    }
}
