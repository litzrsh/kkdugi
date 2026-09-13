package kkdugi.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import kkdugi.core.datasource.annotations.Database;
import kkdugi.core.datasource.aspect.DataSourceRoutingAspect;

class DataSourceRoutingAspectTest {

    interface Target {
        String currentKey();
    }

    @Database("sub")
    static class AnnotatedTarget implements Target {
        @Override
        public String currentKey() {
            return RoutableDataSourceContextHolder.get();
        }
    }

    @Database("sub")
    static class MethodOverrideTarget implements Target {
        @Override
        @Database("tertiary")
        public String currentKey() {
            return RoutableDataSourceContextHolder.get();
        }
    }

    private Target proxy(Object target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new DataSourceRoutingAspect());
        return factory.getProxy();
    }

    @Test
    void setsTheContextKeyFromTheClassLevelAnnotationDuringTheCall() {
        Target target = proxy(new AnnotatedTarget());

        String keyDuringCall = target.currentKey();

        assertThat(keyDuringCall).isEqualTo("sub");
    }

    @Test
    void methodLevelAnnotationOverridesTheClassLevelAnnotation() {
        Target target = proxy(new MethodOverrideTarget());

        String keyDuringCall = target.currentKey();

        assertThat(keyDuringCall).isEqualTo("tertiary");
    }

    @Test
    void clearsTheContextKeyAfterTheCall() {
        Target target = proxy(new AnnotatedTarget());

        target.currentKey();

        assertThat(RoutableDataSourceContextHolder.get()).isNull();
    }
}
