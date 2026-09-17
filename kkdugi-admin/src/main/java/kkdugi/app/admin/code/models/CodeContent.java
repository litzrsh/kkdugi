package kkdugi.app.admin.code.models;

import java.util.Map;

public record CodeContent(
        String id,
        String parentId,
        String code,
        Map<String, CodeLocale> locale,
        String use,
        String extra1,
        String extra2,
        String extra3,
        String extra4,
        String extra5,
        String path,
        Integer level,
        Integer sort
) {
}
