package kkdugi.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kkdugi.core.datasource.RoutableDataSource;
import kkdugi.core.datasource.RoutableDataSourceContextHolder;
import kkdugi.core.datasource.aspect.DataSourceRoutingAspect;

class KkdugiDataSourceAutoConfigurerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KkdugiDataSourceAutoConfigurer.class))
            .withPropertyValues(
                    "kkdugi.datasource.primary-key=main",
                    "kkdugi.datasource.main.driver-class-name=org.h2.Driver",
                    "kkdugi.datasource.main.url=jdbc:h2:mem:kkdugi-main;DB_CLOSE_DELAY=-1",
                    "kkdugi.datasource.main.username=sa",
                    "kkdugi.datasource.main.password=",
                    "kkdugi.datasource.sub.driver-class-name=org.h2.Driver",
                    "kkdugi.datasource.sub.url=jdbc:h2:mem:kkdugi-sub;DB_CLOSE_DELAY=-1",
                    "kkdugi.datasource.sub.username=sa",
                    "kkdugi.datasource.sub.password=");

    @Test
    void exposesTheRoutingAspectAsABean() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(DataSourceRoutingAspect.class));
    }

    @Test
    void routesToThePrimaryKeyByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(RoutableDataSource.class);

            RoutableDataSourceContextHolder.remove();
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                assertThat(metaData.getURL()).contains("kkdugi-main");
            }
        });
    }

    @Test
    void routesToTheKeySetOnTheContextHolder() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            RoutableDataSourceContextHolder.set("sub");
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                assertThat(metaData.getURL()).contains("kkdugi-sub");
            } finally {
                RoutableDataSourceContextHolder.remove();
            }
        });
    }
}
