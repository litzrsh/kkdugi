package kkdugi.web.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Public HTML entry point; authentication is handled by the form authentication filter. */
@Controller
public class LoginController {
    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }
}
