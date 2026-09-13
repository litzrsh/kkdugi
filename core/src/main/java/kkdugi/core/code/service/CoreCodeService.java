package kkdugi.core.code.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.core.code.mapper.CoreCodeMapper;
import kkdugi.core.code.models.Code;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class CoreCodeService {

    public static final String KKDUGI_CODE_CACHE = "KKDUGI_CODE_CACHE";

    private final CacheManager cacheManager;
    private final CoreCodeMapper coreCodeMapper;

    @SuppressWarnings({ "unchecked" })
    @Transactional(readOnly = true)
    public List<Code> findCodes(String path, String locale) {
        List<Code> voList = new ArrayList<>();

        Cache cache = cacheManager.getCache(KKDUGI_CODE_CACHE);
        Cache.ValueWrapper wrapper = cache.get(path);
        if (wrapper == null) {
            voList = coreCodeMapper.findCodes(path);
            cache.put(path, voList);
        } else {
            try {
                voList = (List<Code>) wrapper.get();
            } catch (Exception e) {
                if (log.isDebugEnabled())
                    log.warn("Failed to get code {} from cache : {}", path, e.getMessage());
            }
        }

        return voList.stream()
                .map((c) -> {
                    return Code.from(c, locale);
                })
                .sorted()
                .toList();
    }
}
