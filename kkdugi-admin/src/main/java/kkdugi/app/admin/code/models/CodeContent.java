package kkdugi.app.admin.code.models;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CodeContent {

    private final String id;
    private final String parentId;
    private final String code;
    private final Map<String, CodeLocale> locale;
    private final String use;
    private final String extra1;
    private final String extra2;
    private final String extra3;
    private final String extra4;
    private final String extra5;
    private final String path;
    private final Integer level;
    private final Integer sort;
}
