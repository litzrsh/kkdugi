package kkdugi.core.security.models;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.userdetails.UserDetails;

import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.enums.UserStatus;

/**
 * 세션에 저장되는 사용자 스냅샷. {@code core.security.models}는 내부 전용
 * 계층이라(다른 core.* 도메인 모델과 동일한 관례) 이 클래스를 컨트롤러가
 * 직접 API 응답으로 내려주지 않는다 — 그래서 필드에 {@code @JsonIgnore}를
 * 붙이지 않는다. {@code KKDUGI_SESSION.USER_DTL}에 이 객체 전체를 JSON으로
 * 그대로 저장하기 때문에(SessionUserTypeHandler), 저장용 직렬화에서 필드가
 * 빠지면 세션 스냅샷이 깨진다. "내 정보 조회"처럼 클라이언트에 일부만
 * 보여줘야 하는 API가 생기면, 이 클래스를 그대로 내려주지 말고
 * app.admin/api.admin 계층에 별도 응답 DTO를 만들어 필요한 필드만 옮겨
 * 담는다.
 */
public class SessionUser implements UserDetails {

    private String id;
    private String username;
    private String password;
    private String name;
    private String remarks;
    private String image;
    private Date lastLoginAt;
    private Date lastChangePasswordAt;
    private PasswordStatus passwordStatus;
    private UserStatus status;
    private List<Authority> authorities = new ArrayList<>();
    private Map<String, Object> attributes = new HashMap<>();

    public boolean isNewPassword() {
        return passwordStatus == PasswordStatus.NEWP;
    }

    public boolean isPasswordExpired() {
        return passwordStatus == PasswordStatus.EXPR;
    }

    public boolean isPending() {
        return status == UserStatus.PEND;
    }

    public boolean isDormant() {
        return status == UserStatus.DORM;
    }

    public boolean isResigned() {
        return status == UserStatus.RESN;
    }

    public boolean isSuspended() {
        return status == UserStatus.SUPD;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Date getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Date lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Date getLastChangePasswordAt() {
        return lastChangePasswordAt;
    }

    public void setLastChangePasswordAt(Date lastChangePasswordAt) {
        this.lastChangePasswordAt = lastChangePasswordAt;
    }

    public PasswordStatus getPasswordStatus() {
        return passwordStatus;
    }

    public void setPasswordStatus(PasswordStatus passwordStatus) {
        this.passwordStatus = passwordStatus;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public List<Authority> getAuthorities() {
        return authorities;
    }

    public void setAuthorities(List<Authority> authorities) {
        this.authorities = authorities;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
