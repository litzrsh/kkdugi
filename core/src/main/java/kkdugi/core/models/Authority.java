package kkdugi.core.models;

import java.io.Serial;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;

import kkdugi.core.Constants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class Authority implements GrantedAuthority {

    @Serial
    private static final long serialVersionUID = Constants.SERIAL_ID;

    private String id;
    private String authority;
    private String type;
    private String name;
    private boolean expired;

    public static List<Authority> getAnonymousAuthorities() {
        Authority authority = new Authority();
        authority.setId(Constants.ANONYMOUS);
        authority.setAuthority(Constants.ANONYMOUS);
        authority.setName(Constants.ANONYMOUS);
        authority.setExpired(false);
        return List.of(authority);
    }
}
