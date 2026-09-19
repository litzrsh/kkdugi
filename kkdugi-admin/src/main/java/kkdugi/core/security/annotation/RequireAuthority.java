package kkdugi.core.security.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import kkdugi.core.enums.Rbac;

@Inherited
@Documented
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuthority {

    /** All listed permissions are required (AND). Empty means menu membership only. */
    Rbac[] value() default {};

    /** Bind a feature API to the originating menu's program. Empty permits shared APIs. */
    String program() default "";

    /** Derive WRTE/DELT from exactly one AuthorityBatch argument. */
    boolean batch() default false;

    /** Only permits the reserved context on GET /api/v1.0/menu with a valid session. */
    boolean allowShell() default false;
}
