package kkdugi.web.admin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import kkdugi.app.code.models.Code;
import kkdugi.app.code.service.CodeService;
import kkdugi.web.admin.models.LanguageOption;

/** 공통코드 /SYS/LANG의 extra1을 실제 로케일 코드로 사용한다. */
@Service
public class AdminLanguageService {
    private final CodeService codes;

    public AdminLanguageService(CodeService codes) { this.codes = codes; }

    public List<LanguageOption> languages(Locale locale) {
        return toOptions(codes.findChildren("/SYS/LANG", locale.toString()));
    }

    public static List<LanguageOption> toOptions(List<Code> rows) {
        var options = new LinkedHashMap<String, LanguageOption>();
        for (Code row : rows) {
            String code = row.getExtra1() == null ? "" : row.getExtra1().trim();
            if (!code.matches("[A-Za-z]{2,8}(?:[_-][A-Za-z0-9]{1,8})*")) continue;
            String label = row.getName() == null || row.getName().isBlank() ? code : row.getName().trim();
            options.putIfAbsent(code, new LanguageOption(code, label));
        }
        return List.copyOf(options.values());
    }
}
