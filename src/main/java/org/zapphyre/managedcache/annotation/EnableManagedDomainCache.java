package org.zapphyre.managedcache.annotation;

import org.springframework.context.annotation.Import;
import org.zapphyre.managedcache.config.ManagedDomainCacheRegistrar;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(ManagedDomainCacheRegistrar.class)
public @interface EnableManagedDomainCache {

    /**
     * Base packages to scan for @CacheManaged DTOs in the default domain.
     * Used only if domains() is empty.
     */
    String[] dtoBasePackages() default {};

    /**
     * Multiple domain configurations. If specified, dtoBasePackages is ignored.
     */
    DomainConfig[] domains() default {};

    @interface DomainConfig {
        String name();
        String[] basePackages();
    }
}
