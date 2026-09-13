package kkdugi.core.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import kkdugi.core.Constants;
import kkdugi.core.models.User;

public abstract class SessionUtils {

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_CLIENT_IP"
    };

    public static User getUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assert auth != null;
            return (User) auth.getPrincipal();
        } catch (Exception e) {
            return User.getAnonymousUser();
        }
    }

    public static boolean isAnonymous() {
        return getUser().getAuthorities().stream()
                .anyMatch(a -> Constants.ANONYMOUS.equals(a.getAuthority()));
    }

    public static boolean isSystemAdmin() {
        return getUser().getAuthorities().stream()
                .anyMatch(a -> Constants.SYSTEM_ADMIN.equals(a.getAuthority()));
    }

    public static String getClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
