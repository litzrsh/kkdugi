package kkdugi.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import kkdugi.core.datasource.RoutableDataSource;
import kkdugi.core.datasource.aspect.DataSourceRoutingAspect;
import kkdugi.core.props.DataSourceEntryProperties;
import kkdugi.core.props.KkdugiDataSourceProperties;

@Configuration
@EnableConfigurationProperties(KkdugiDataSourceProperties.class)
public class KkdugiDataSourceAutoConfigurer {

    private static final String DATASOURCE_PREFIX = "kkdugi.datasource";
    private static final String PRIMARY_KEY_PROPERTY = "primary-key";

    @Bean
    DataSource dataSource(Environment environment, KkdugiDataSourceProperties properties) {
        Map<String, DataSourceEntryProperties> entries = resolveEntries(environment);
        if (entries.isEmpty()) {
            throw new IllegalStateException("No datasource configured under " + DATASOURCE_PREFIX);
        }

        Map<Object, Object> targetDataSources = new LinkedHashMap<>();
        entries.forEach((key, entry) -> targetDataSources.put(key, buildDataSource(entry)));

        String primaryKey = properties.getPrimaryKey();
        RoutableDataSource routableDataSource = new RoutableDataSource(primaryKey);
        routableDataSource.setTargetDataSources(targetDataSources);
        routableDataSource.setDefaultTargetDataSource(targetDataSources.get(primaryKey));
        routableDataSource.afterPropertiesSet();
        return routableDataSource;
    }

    @Bean
    DataSourceRoutingAspect dataSourceRoutingAspect() {
        return new DataSourceRoutingAspect();
    }

    private Map<String, DataSourceEntryProperties> resolveEntries(Environment environment) {
        Binder binder = Binder.get(environment);
        Map<String, Object> raw = binder
                .bind(DATASOURCE_PREFIX, Bindable.mapOf(String.class, Object.class))
                .orElseGet(LinkedHashMap::new);

        Map<String, DataSourceEntryProperties> entries = new LinkedHashMap<>();
        for (String key : raw.keySet()) {
            if (PRIMARY_KEY_PROPERTY.equals(key)) {
                continue;
            }
            binder.bind(DATASOURCE_PREFIX + "." + key, Bindable.of(DataSourceEntryProperties.class))
                    .ifBound(entry -> entries.put(key, entry));
        }
        return entries;
    }

    private DataSource buildDataSource(DataSourceEntryProperties entry) {
        return DataSourceBuilder.create()
                .driverClassName(entry.getDriverClassName())
                .url(entry.getUrl())
                .username(entry.getUsername())
                .password(entry.getPassword())
                .build();
    }
}
