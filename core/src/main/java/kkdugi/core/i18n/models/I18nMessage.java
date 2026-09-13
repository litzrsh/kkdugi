package kkdugi.core.i18n.models;

import java.io.Serial;
import java.io.Serializable;

import kkdugi.core.Constants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class I18nMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = Constants.SERIAL_ID;

    private String lang;
    private String code;
    private String message;
}
