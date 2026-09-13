package kkdugi.core.datasource.aspect;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;

import kkdugi.core.datasource.RoutableDataSourceContextHolder;
import kkdugi.core.datasource.annotations.Database;

@Aspect
public class DataSourceRoutingAspect {

    @Around("@annotation(kkdugi.core.datasource.Database) || @within(kkdugi.core.datasource.Database)")
    public Object route(ProceedingJoinPoint joinPoint) throws Throwable {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        Method declaredMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method method = AopUtils.getMostSpecificMethod(declaredMethod, targetClass);

        Database database = AnnotatedElementUtils.findMergedAnnotation(method, Database.class);
        if (database == null) {
            database = AnnotatedElementUtils.findMergedAnnotation(targetClass, Database.class);
        }

        if (database == null) {
            return joinPoint.proceed();
        }

        RoutableDataSourceContextHolder.set(database.value());
        try {
            return joinPoint.proceed();
        } finally {
            RoutableDataSourceContextHolder.remove();
        }
    }
}
