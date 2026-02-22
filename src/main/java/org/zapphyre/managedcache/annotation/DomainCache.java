package org.zapphyre.managedcache.annotation;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainCache {
    /**
     * Segment name for this cache. Defaults to "default".
     */
    String segment() default "default";

    /**
     * SpEL expression for cache key. If empty, defaults to method name + arguments.
     */
    String key() default "";
}