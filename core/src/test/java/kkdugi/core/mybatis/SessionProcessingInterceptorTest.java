package kkdugi.core.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.stereotype.Component;

import kkdugi.core.models.User;
import kkdugi.core.util.SessionUtils;

class SessionProcessingInterceptorTest {

    private final SessionProcessingInterceptor interceptor = new SessionProcessingInterceptor();

    private User user(String id) {
        User user = new User();
        user.setId(id);
        user.setUsername("tester");
        return user;
    }

    private Invocation queryInvocation(Object parameter) throws NoSuchMethodException {
        Method method = Executor.class.getMethod("query",
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class);
        Object[] args = { mock(MappedStatement.class), parameter, mock(RowBounds.class), mock(ResultHandler.class) };
        return new Invocation(mock(Executor.class), method, args);
    }

    private Invocation updateInvocation(Object parameter) throws NoSuchMethodException {
        Method method = Executor.class.getMethod("update", MappedStatement.class, Object.class);
        Object[] args = { mock(MappedStatement.class), parameter };
        return new Invocation(mock(Executor.class), method, args);
    }

    @Test
    void query_addsSessionToAnExistingMapParameter_withoutLosingOtherEntries() throws Throwable {
        Map<String, Object> original = new HashMap<>();
        original.put("path", "SOME_PATH");
        User currentUser = user("u1");

        try (MockedStatic<SessionUtils> sessionUtils = mockStatic(SessionUtils.class)) {
            sessionUtils.when(SessionUtils::getUser).thenReturn(currentUser);

            Invocation invocation = queryInvocation(original);
            interceptor.intercept(invocation);

            Object param = invocation.getArgs()[1];
            assertThat(param).isSameAs(original);
            assertThat(((Map<?, ?>) param).get("session")).isEqualTo(currentUser);
            assertThat(((Map<?, ?>) param).get("path")).isEqualTo("SOME_PATH");
        }
    }

    @Test
    void update_addsSessionToAnExistingMapParameter() throws Throwable {
        Map<String, Object> original = new HashMap<>();
        original.put("id", 42);
        User currentUser = user("u2");

        try (MockedStatic<SessionUtils> sessionUtils = mockStatic(SessionUtils.class)) {
            sessionUtils.when(SessionUtils::getUser).thenReturn(currentUser);

            Invocation invocation = updateInvocation(original);
            interceptor.intercept(invocation);

            Map<?, ?> param = (Map<?, ?>) invocation.getArgs()[1];
            assertThat(param.get("session")).isEqualTo(currentUser);
            assertThat(param.get("id")).isEqualTo(42);
        }
    }

    @Test
    void existingSessionEntryInTheMapIsOverwrittenWithTheCurrentUser() throws Throwable {
        Map<String, Object> original = new HashMap<>();
        original.put("session", user("stale"));
        User currentUser = user("fresh");

        try (MockedStatic<SessionUtils> sessionUtils = mockStatic(SessionUtils.class)) {
            sessionUtils.when(SessionUtils::getUser).thenReturn(currentUser);

            Invocation invocation = updateInvocation(original);
            interceptor.intercept(invocation);

            assertThat(((Map<?, ?>) invocation.getArgs()[1]).get("session")).isEqualTo(currentUser);
        }
    }

    @Test
    void nullParameter_isWrappedIntoAMapContainingOnlyTheSession() throws Throwable {
        User currentUser = user("u3");

        try (MockedStatic<SessionUtils> sessionUtils = mockStatic(SessionUtils.class)) {
            sessionUtils.when(SessionUtils::getUser).thenReturn(currentUser);

            Invocation invocation = queryInvocation(null);
            interceptor.intercept(invocation);

            Object param = invocation.getArgs()[1];
            assertThat(param).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) param).get("session")).isEqualTo(currentUser);
        }
    }

    static class Vo {
        private final String name = "foo";

        public String getName() {
            return name;
        }
    }

    @Test
    void pojoParameter_isWrappedSoOriginalPropertiesStillResolve_alongsideSession() throws Throwable {
        User currentUser = user("u4");
        Vo parameter = new Vo();

        try (MockedStatic<SessionUtils> sessionUtils = mockStatic(SessionUtils.class)) {
            sessionUtils.when(SessionUtils::getUser).thenReturn(currentUser);

            Invocation invocation = updateInvocation(parameter);
            interceptor.intercept(invocation);

            Map<?, ?> param = (Map<?, ?>) invocation.getArgs()[1];
            assertThat(param.get("session")).isEqualTo(currentUser);
            assertThat(param.get("name")).isEqualTo("foo");
            assertThat(param.containsKey("name")).isTrue();
            assertThat(param.containsKey("missingProperty")).isFalse();
            assertThat(param.get("missingProperty")).isNull();
        }
    }

    @Test
    void plugin_wrapsExecutorsSoInterceptedMethodsAreProxied() {
        Executor executor = mock(Executor.class);

        Object wrapped = interceptor.plugin(executor);

        assertThat(wrapped).isInstanceOf(Executor.class).isNotSameAs(executor);
    }

    @Test
    void plugin_leavesTargetsWithNoMatchingSignatureUntouched() {
        Object target = new Object();

        Object wrapped = interceptor.plugin(target);

        assertThat(wrapped).isSameAs(target);
    }

    @Test
    void interceptorIsRegisteredAsASpringBeanSoMybatisAutoConfigurationPicksItUpAsAPlugin() {
        assertThat(SessionProcessingInterceptor.class).hasAnnotation(Component.class);
        assertThat(Interceptor.class).isAssignableFrom(SessionProcessingInterceptor.class);
    }
}
