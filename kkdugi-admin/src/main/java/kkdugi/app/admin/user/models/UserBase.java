package kkdugi.app.admin.user.models;

import java.time.LocalDateTime;

import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.enums.UserStatus;
import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code kkdugi_user_base} 한 행. {@code password}는 인코딩된 값이며 INSERT에만 쓰고 조회 결과에는
 * 채우지 않는다(SELECT 목록에 {@code user_pwd}가 없다).
 */
@Getter
@Setter
public class UserBase extends BaseModel {

    private String id;
    private String username;
    private String name;
    private String remarks;
    private String image;
    private String email;
    private UserStatus status;
    private String password;
    private PasswordStatus passwordStatus;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lastChangePasswordAt;

    public UserBase() {
    }

    public UserBase(String id, String username, String name, String remarks, String image, String email,
            UserStatus status) {
        this.id = id;
        this.username = username;
        this.name = name;
        this.remarks = remarks;
        this.image = image;
        this.email = email;
        this.status = status;
    }
}
