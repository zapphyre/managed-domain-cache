package org.zapphyre.managedcache.annotation;

import org.springframework.context.annotation.Import;
import org.zapphyre.managedcache.config.ManagedDomainCacheRegistrar;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(ManagedDomainCacheRegistrar.class)
public @interface EnableManagedDomainCache {
    /**
     * Base packages to scan for @CacheManaged annotated DTOs.
     */
    String[] basePackages();
}
