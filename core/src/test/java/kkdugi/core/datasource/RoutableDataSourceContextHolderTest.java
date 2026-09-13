package kkdugi.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoutableDataSourceContextHolderTest {

    @Test
    void removeClearsTheStoredKey() {
        RoutableDataSourceContextHolder.set("sub");
        RoutableDataSourceContextHolder.remove();
        assertThat(RoutableDataSourceContextHolder.get()).isNull();
    }
}
