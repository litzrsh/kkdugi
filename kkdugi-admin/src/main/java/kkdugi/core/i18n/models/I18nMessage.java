package kkdugi.core.i18n.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class I18nMessage extends BaseModel {

    private String msgCode;
    private String langCode;
    private String msgText;

    public I18nMessage() {
    }

    public I18nMessage(String msgCode, String langCode, String msgText) {
        this.msgCode = msgCode;
        this.langCode = langCode;
        this.msgText = msgText;
    }
}
