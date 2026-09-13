package kkdugi.core.mybatis;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import kkdugi.core.models.User;
import kkdugi.core.util.SessionUtils;

/**
 * Injects the current {@link SessionUtils#getUser()} into every MyBatis statement
 * parameter under the {@value #SESSION_PARAM_KEY} key, so mapper XML can reference
 * it (e.g. {@code #{session.id}}) on select/insert/update/delete alike.
 *
 * <p>Registered automatically: mybatis-spring-boot-starter picks up any
 * {@link Interceptor} bean and adds it as a plugin, so {@code @Component} alone
 * is enough wiring.
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }),
        @Signature(type = Executor.class, method = "update",
                args = { MappedStatement.class, Object.class })
})
public class SessionProcessingInterceptor implements Interceptor {

    public static final String SESSION_PARAM_KEY = "session";

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        args[1] = injectSession(args[1]);
        return invocation.proceed();
    }

    private Object injectSession(Object parameter) {
        User user = SessionUtils.getUser();

        if (parameter instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramMap = (Map<String, Object>) rawMap;
            paramMap.put(SESSION_PARAM_KEY, user);
            return paramMap;
        }

        return new SessionParamMap(parameter, user);
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // no configurable properties
    }

    /**
     * Wraps a non-Map parameter (a plain VO, or {@code null}) so that
     * {@code #{session}} resolves while every original property of the wrapped
     * object (e.g. {@code #{name}}) keeps resolving exactly as before.
     */
    static final class SessionParamMap extends HashMap<String, Object> {

        private final transient MetaObject metaObject;

        SessionParamMap(Object original, User user) {
            this.metaObject = original == null ? null : SystemMetaObject.forObject(original);
            put(SESSION_PARAM_KEY, user);
        }

        @Override
        public boolean containsKey(Object key) {
            if (super.containsKey(key)) {
                return true;
            }
            return metaObject != null && key instanceof String name && metaObject.hasGetter(name);
        }

        @Override
        public Object get(Object key) {
            if (super.containsKey(key)) {
                return super.get(key);
            }
            if (metaObject != null && key instanceof String name && metaObject.hasGetter(name)) {
                return metaObject.getValue(name);
            }
            return null;
        }
    }
}
