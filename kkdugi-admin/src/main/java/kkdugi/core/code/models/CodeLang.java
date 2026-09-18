package kkdugi.core.code.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodeLang extends BaseModel {

    private String codeId;
    private String langCode;
    private String name;
    private String remarks;

    public CodeLang() {
    }

    public CodeLang(String codeId, String langCode, String name, String remarks) {
        this.codeId = codeId;
        this.langCode = langCode;
        this.name = name;
        this.remarks = remarks;
    }
}
