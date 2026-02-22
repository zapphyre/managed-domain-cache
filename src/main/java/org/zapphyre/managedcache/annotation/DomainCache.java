package org.zapphyre.managedcache.annotation;


import org.zapphyre.managedcache.pojo.ECachingOperation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainCache {
    /**
     * Segment name for caching/eviction. Defaults to "default".
     */
    String segment() default "default";

    ECachingOperation operation() default ECachingOperation.READ;
    String key() default "";
    boolean evictAtomic() default false;
    String evictByName() default "";
}