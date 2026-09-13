package kkdugi.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreCacheManagerConfigurer {

    @ConditionalOnMissingBean(CacheManager.class)
    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager();
    }
}
