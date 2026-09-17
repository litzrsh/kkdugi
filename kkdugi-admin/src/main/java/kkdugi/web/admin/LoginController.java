package kkdugi.web.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Public HTML entry point; authentication is handled by the existing JSON filter. */
@Controller
public class LoginController {
    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }
}
