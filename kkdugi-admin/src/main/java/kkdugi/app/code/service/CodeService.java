package kkdugi.app.code.service;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.code.mapper.CodeMapper;
import kkdugi.app.code.models.Code;

@Service
public class CodeService {

    public static final String CACHE_NAME = "KKDUGI_CODE_CACHE";

    private final CodeMapper mapper;

    public CodeService(CodeMapper mapper) {
        this.mapper = mapper;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "'children@@' + #path + '@@' + #langCode")
    @Transactional(readOnly = true)
    public List<Code> findChildren(String path, String langCode) {
        return mapper.findChildren(path, langCode);
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#path + '@@' + #langCode")
    @Transactional(readOnly = true)
    public List<Code> findCodes(String path, String langCode) {
        return mapper.findAll(path, langCode);
    }
}
