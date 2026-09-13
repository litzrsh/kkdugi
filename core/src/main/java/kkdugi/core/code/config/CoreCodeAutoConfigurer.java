package kkdugi.core.code.config;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kkdugi.core.code.mapper.CoreCodeMapper;
import kkdugi.core.code.service.CoreCodeService;

@Configuration
public class CoreCodeAutoConfigurer {

    @Bean
    CoreCodeService coreCodeService(CacheManager cacheManager, CoreCodeMapper coreCodeMapper) {
        return new CoreCodeService(cacheManager, coreCodeMapper);
    }
}
