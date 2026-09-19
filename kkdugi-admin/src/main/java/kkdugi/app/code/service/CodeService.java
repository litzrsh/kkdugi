package kkdugi.app.code.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.code.mapper.CodeMapper;
import kkdugi.app.code.models.Code;
import kkdugi.app.code.models.CodeParams;
import kkdugi.core.models.Page;

@Service
public class CodeService {

    private final CodeMapper mapper;

    public CodeService(CodeMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<Code> findChildren(CodeParams params, String langCode) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());

        List<Code> contents = mapper.findChildren(
                params.getParentId(), params.getPath(), langCode, params.getOffset(), params.getLimit());

        return Page.of(contents, params);
    }
}
