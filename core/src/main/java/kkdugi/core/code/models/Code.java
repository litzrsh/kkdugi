package kkdugi.core.code.models;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

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
public class Code implements Comparable<Code> {

    @Serial
    private static final long serialVersionUID = Constants.SERIAL_ID;

    private String value;
    private String text;
    private String remarks;
    private String extra1;
    private String extra2;
    private String extra3;
    private String extra4;
    private String extra5;
    private String path;
    private int sort;
    @JsonIgnore
    private List<CodeLang> langs = new ArrayList<>();

    @Override
    public int compareTo(Code o) {
        return sort - o.sort;
    }

    public static Code from(Code o, String locale) {
        Code code = new Code();
        CodeLang lang = o.langs.stream().filter(i -> i.getLang().equals(locale))
                .findAny().orElse(null);
        if (lang == null) {
            if (o.langs.size() > 0) {
                code.setText(o.langs.get(0).getText());
                code.setRemarks(o.langs.get(0).getRemarks());
            }
        } else {
            code.setText(lang.getText());
            code.setRemarks(lang.getRemarks());
        }
        code.setValue(o.getValue());
        code.setExtra1(o.getExtra1());
        code.setExtra2(o.getExtra2());
        code.setExtra3(o.getExtra3());
        code.setExtra4(o.getExtra4());
        code.setExtra5(o.getExtra5());
        code.setPath(o.getPath());
        code.setSort(o.getSort());
        return code;
    }
}
