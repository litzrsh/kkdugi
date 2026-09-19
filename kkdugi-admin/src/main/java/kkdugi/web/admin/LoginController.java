package kkdugi.web.admin;

import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import kkdugi.web.admin.service.AdminLanguageService;

/** Public HTML entry point; authentication is handled by the form authentication filter. */
@Controller
public class LoginController {
    private final AdminLanguageService languageService;

    public LoginController(AdminLanguageService languageService) {
        this.languageService = languageService;
    }

    @GetMapping("/login")
    public String login(Model model, Locale locale) {
        var languages = languageService.languages(locale);
        model.addAttribute("languages", languages);
        // Match equivalent locale spellings without rewriting the configured extra1 value.
        model.addAttribute("selectedLanguage", languages.stream()
                .filter(option -> Locale.forLanguageTag(option.getCode().replace('_', '-')).equals(locale))
                .map(option -> option.getCode()).findFirst().orElse(""));
        return "auth/login";
    }
}
