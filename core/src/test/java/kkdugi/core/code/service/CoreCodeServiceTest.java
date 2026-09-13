package kkdugi.core.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import kkdugi.core.code.mapper.CoreCodeMapper;
import kkdugi.core.code.models.Code;
import kkdugi.core.code.models.CodeLang;

class CoreCodeServiceTest {

    private CoreCodeMapper coreCodeMapper;
    private ConcurrentMapCache cache;
    private CoreCodeService coreCodeService;

    @BeforeEach
    void setUp() {
        coreCodeMapper = mock(CoreCodeMapper.class);

        // Real cache backing store so we can verify actual caching behavior,
        // not just that mocked methods were called.
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        cacheManager.setCacheNames(List.of(CoreCodeService.KKDUGI_CODE_CACHE));
        cache = (ConcurrentMapCache) cacheManager.getCache(CoreCodeService.KKDUGI_CODE_CACHE);

        coreCodeService = new CoreCodeService(cacheManager, coreCodeMapper);
    }

    private Code code(String value, int sort, CodeLang... langs) {
        Code c = new Code();
        c.setValue(value);
        c.setPath("SOME_PATH");
        c.setSort(sort);
        c.setLangs(List.of(langs));
        return c;
    }

    @Test
    void cacheMiss_fetchesFromMapperAndPopulatesCache() {
        List<Code> fromDb = List.of(code("A", 1, new CodeLang("ko", "가", null)));
        when(coreCodeMapper.findCodes("PATH")).thenReturn(fromDb);

        coreCodeService.findCodes("PATH", "ko");

        verify(coreCodeMapper, times(1)).findCodes("PATH");
        assertThat(cache.get("PATH")).isNotNull();
    }

    @Test
    void cacheHit_doesNotHitMapperAgain() {
        List<Code> fromDb = List.of(code("A", 1, new CodeLang("ko", "가", null)));
        when(coreCodeMapper.findCodes("PATH")).thenReturn(fromDb);

        coreCodeService.findCodes("PATH", "ko");
        coreCodeService.findCodes("PATH", "ko");
        coreCodeService.findCodes("PATH", "en");

        verify(coreCodeMapper, times(1)).findCodes("PATH");
    }

    @Test
    void cacheIsPrewarmed_neverHitsMapper() {
        List<Code> preloaded = List.of(code("A", 1, new CodeLang("ko", "가", null)));
        cache.put("PATH", preloaded);

        coreCodeService.findCodes("PATH", "ko");

        verify(coreCodeMapper, never()).findCodes(anyString());
    }

    @Test
    void differentPaths_areCachedIndependently() {
        when(coreCodeMapper.findCodes("PATH_A"))
                .thenReturn(List.of(code("A", 1, new CodeLang("ko", "A-ko", null))));
        when(coreCodeMapper.findCodes("PATH_B"))
                .thenReturn(List.of(code("B", 1, new CodeLang("ko", "B-ko", null))));

        coreCodeService.findCodes("PATH_A", "ko");
        coreCodeService.findCodes("PATH_B", "ko");
        coreCodeService.findCodes("PATH_A", "ko");
        coreCodeService.findCodes("PATH_B", "ko");

        verify(coreCodeMapper, times(1)).findCodes("PATH_A");
        verify(coreCodeMapper, times(1)).findCodes("PATH_B");
    }

    @Test
    void findCodes_resolvesTextForRequestedLocale() {
        Code sourceCode = code("A", 1,
                new CodeLang("ko", "한국어", "코멘트"),
                new CodeLang("en", "English", "comment"));
        when(coreCodeMapper.findCodes("PATH")).thenReturn(List.of(sourceCode));

        List<Code> result = coreCodeService.findCodes("PATH", "en");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("English");
        assertThat(result.get(0).getRemarks()).isEqualTo("comment");
    }

    @Test
    void findCodes_fallsBackToFirstLang_whenLocaleNotFound() {
        Code sourceCode = code("A", 1,
                new CodeLang("ko", "한국어", "코멘트"),
                new CodeLang("en", "English", "comment"));
        when(coreCodeMapper.findCodes("PATH")).thenReturn(List.of(sourceCode));

        List<Code> result = coreCodeService.findCodes("PATH", "fr");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("한국어");
        assertThat(result.get(0).getRemarks()).isEqualTo("코멘트");
    }

    @Test
    void findCodes_reLocalizesCachedValue_onSubsequentCallWithDifferentLocale() {
        Code sourceCode = code("A", 1,
                new CodeLang("ko", "한국어", null),
                new CodeLang("en", "English", null));
        when(coreCodeMapper.findCodes("PATH")).thenReturn(List.of(sourceCode));

        List<Code> koResult = coreCodeService.findCodes("PATH", "ko");
        List<Code> enResult = coreCodeService.findCodes("PATH", "en");

        assertThat(koResult.get(0).getText()).isEqualTo("한국어");
        assertThat(enResult.get(0).getText()).isEqualTo("English");
        verify(coreCodeMapper, times(1)).findCodes("PATH");
    }

    @Test
    void findCodes_sortsResultsBySortField() {
        Code first = code("A", 2, new CodeLang("ko", "둘", null));
        Code second = code("B", 1, new CodeLang("ko", "하나", null));
        when(coreCodeMapper.findCodes("PATH")).thenReturn(List.of(first, second));

        List<Code> result = coreCodeService.findCodes("PATH", "ko");

        assertThat(result).extracting(code -> code.getValue()).containsExactly("B", "A");
    }
}
