package org.zapphyre.managedcache.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainCacheEvict {
    /**
     * Segment name for this eviction. Defaults to "default".
     */
    String segment() default "default";

    /**
     * Optional explicit entity type to evict. If not set, inferred from method parameters/return.
     */
    Class<?> type() default Object.class; // Object.class means "not specified"

    /**
     * If true, evict only the entity's own cache region (no graph propagation).
     */
    boolean atomic() default false;

    /**
     * SpEL expression for a specific cache key to evict (within the entity's region).
     */
    String key() default "";
}