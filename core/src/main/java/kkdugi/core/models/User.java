package kkdugi.core.models;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;

import kkdugi.core.Constants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Serial
    private static final long serialVersionUID = Constants.SERIAL_ID;

    private String id;
    private String username;
    @JsonIgnore
    private String password;
    private String name;
    @JsonIgnore
    private Date lastLoginAt;
    @JsonIgnore
    private Date lastChangePasswordAt;
    private boolean passwordExpired;
    @JsonIgnore
    private String status;
    private List<Authority> authorities = new ArrayList<>();
    private List<Menu> menus = new ArrayList<>();

    public static User getAnonymousUser() {
        User user = new User();
        user.setUsername(Constants.ANONYMOUS);
        user.setPassword(Constants.ANONYMOUS);
        user.setName(Constants.ANONYMOUS);
        user.setAuthorities(Authority.getAnonymousAuthorities());
        return user;
    }
}
