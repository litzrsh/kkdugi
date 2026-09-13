package kkdugi.core.datasource;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import kkdugi.core.util.CommonUtils;

public class RoutableDataSource extends AbstractRoutingDataSource {

    private final String primaryKey;

    public RoutableDataSource(String primvayKey) {
        this.primaryKey = primvayKey;
    }

    @Override
    protected @Nullable Object determineCurrentLookupKey() {
        String key = RoutableDataSourceContextHolder.get();
        return CommonUtils.isEmpty(key) ? this.primaryKey : key;
    }
}
