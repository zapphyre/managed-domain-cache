package org.zapphyre.managedcache.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CacheManaged {
    /**
     * Segment name for this entity graph. Defaults to "default".
     */
    String segment() default "default";

    /**
     * Direct dependent entity classes within the same segment.
     */
    Class<?>[] dependants() default {};
}