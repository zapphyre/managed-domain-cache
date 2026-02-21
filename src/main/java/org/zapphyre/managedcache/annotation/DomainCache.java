package org.zapphyre.managedcache.annotation;

import org.springframework.cache.interceptor.CacheOperation;
import org.zapphyre.managedcache.pojo.ECachingOperation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainCache {
    String domain() default "default";
    ECachingOperation operation() default ECachingOperation.READ;
    String key() default "";
    boolean evictAtomic() default false;
    String evictByName() default "";
}