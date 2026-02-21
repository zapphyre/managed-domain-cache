package org.zapphyre.managedcache.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.ResolvableType;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.zapphyre.managedcache.annotation.DomainCache;
import org.zapphyre.managedcache.config.DomainCacheRegistry;
import org.zapphyre.managedcache.pojo.ECachingOperation;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ManagedCacheAspect {

    private final CacheManager cacheManager;
    private final DomainCacheRegistry graphRegistry;
    private final SpelExpressionParser spel = new SpelExpressionParser();

    @Pointcut("@annotation(domainCache)")
    public void cacheableMethod(DomainCache domainCache) {}

    // ============================================================
    // READ
    // ============================================================

    @Around(value = "cacheableMethod(cfg)", argNames = "pjp, cfg")
    public Object handleRead(ProceedingJoinPoint pjp, DomainCache cfg) throws Throwable {

        if (cfg.operation() != ECachingOperation.READ) {
            return pjp.proceed();
        }

        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Object[] args = pjp.getArgs();
        String domain = cfg.domain();

        // Determine the entity type (the return type or collection element type)
        Class<?> entityType = resolveEntityType(method);
        if (entityType == null || !graphRegistry.isCacheable(entityType, domain)) {
            log.debug("Entity type not cacheable or cannot be resolved for {}. Skipping cache.", method.getName());
            return pjp.proceed();
        }

        // Generate cache key
        Object key = generateKey(cfg.key(), method, args);
        if (key == null) {
            log.debug("No cache key generated for {}. Proceeding without cache.", method.getName());
            return pjp.proceed();
        }

        // Get cache region for this entity type
        Cache cache = cacheManager.getCache(entityType.getName());
        if (cache == null) {
            log.debug("No cache region found for {}. Proceeding without cache.", entityType.getName());
            return pjp.proceed();
        }

        // Read-through: try cache, else invoke method and cache result
        return readThrough(cache, key, entityType, domain, () -> {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });
    }

    private Object readThrough(Cache cache,
                               Object key,
                               Class<?> entityType,
                               String domain,
                               Supplier<Object> execution) {
        // Try cache hit
        Cache.ValueWrapper wrapper = cache.get(key);
        if (wrapper != null) {
            log.debug("Cache HIT [{}:{}] in domain '{}'", cache.getName(), key, domain);
            return wrapper.get();
        }

        log.debug("Cache MISS [{}:{}] in domain '{}'", cache.getName(), key, domain);

        // Invoke the method
        Object result = execution.get();

        // Cache the result if it matches the expected entity type (or collection thereof)
        if (result != null) {
            if (result instanceof Collection) {
                Collection<?> collection = (Collection<?>) result;
                if (!collection.isEmpty()) {
                    Class<?> elementType = collection.iterator().next().getClass();
                    if (entityType.isAssignableFrom(elementType) && graphRegistry.isCacheable(elementType, domain)) {
                        cache.put(key, result);
                        log.debug("Cached collection [{}:{}] in domain '{}'", cache.getName(), key, domain);
                    }
                }
            } else if (entityType.isAssignableFrom(result.getClass()) && graphRegistry.isCacheable(result.getClass(), domain)) {
                cache.put(key, result);
                log.debug("Cached single result [{}:{}] in domain '{}'", cache.getName(), key, domain);
            }
        }

        return result;
    }

    // ============================================================
    // WRITE
    // ============================================================

    @AfterReturning(
            pointcut = "cacheableMethod(cfg)",
            returning = "result",
            argNames = "jp,cfg,result"
    )
    public void handleWrite(JoinPoint jp,
                            DomainCache cfg,
                            Object result) {

        if (cfg.operation() != ECachingOperation.WRITE) {
            return;
        }

        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        Object[] args = jp.getArgs();
        String domain = cfg.domain();

        Optional.ofNullable(extractWrittenEntityType(result, args, domain))
                .filter(entityType -> graphRegistry.isCacheable(entityType, domain))
                .map(Class::getName)
                .map(cacheManager::getCache)
                .ifPresent(cache -> handleEviction(cfg, method, args, domain).accept(cache));
    }

    /**
     * Eviction strategy builder, now domain-aware.
     */
    private Consumer<Cache> handleEviction(DomainCache cfg,
                                           Method method,
                                           Object[] args,
                                           String domain) {

        return cache -> {
            Object namedKey = generateKey(cfg.evictByName(), method, args);

            if (namedKey != null) {
                cache.evict(namedKey);
                log.debug("Evicted [{}:{}] in domain '{}'", cache.getName(), namedKey, domain);
                if (cfg.evictAtomic()) return;
            }

            if (cfg.evictAtomic()) {
                cache.clear();
                log.debug("Atomically cleared region [{}] in domain '{}'", cache.getName(), domain);
                return;
            }

            // Non-atomic: invalidate entire graph for this entity type
            Class<?> entityType = safeClass(cache.getName());
            if (entityType == null) return;

            graphRegistry.getAffectedClasses(entityType, domain)
                    .stream()
                    .map(Class::getName)
                    .map(cacheManager::getCache)
                    .filter(Objects::nonNull)
                    .forEach(c -> {
                        c.clear();
                        log.debug("Hierarchically cleared region [{}] in domain '{}'", c.getName(), domain);
                    });
        };
    }

    // ============================================================
    // ENTITY RESOLUTION (domain-aware)
    // ============================================================

    private Class<?> resolveEntityType(Method method) {
        Class<?> returnType = method.getReturnType();

        if (!Collection.class.isAssignableFrom(returnType)) {
            return returnType;
        }

        return ResolvableType.forMethodReturnType(method)
                .getGeneric(0)
                .resolve();
    }

    private Class<?> extractEntityType(Object source) {
        if (source == null) return null;
        if (source instanceof Collection<?> col && !col.isEmpty()) {
            return col.iterator().next().getClass();
        }
        return source.getClass();
    }

    private Class<?> extractWrittenEntityType(Object result,
                                              Object[] args,
                                              String domain) {
        // First check result
        Class<?> fromResult = extractEntityType(result);
        if (fromResult != null && graphRegistry.isCacheable(fromResult, domain)) {
            return fromResult;
        }

        // Then check each argument
        for (Object arg : args) {
            Class<?> type = extractEntityType(arg);
            if (type != null && graphRegistry.isCacheable(type, domain)) {
                return type;
            }
        }

        return null;
    }

    private Class<?> safeClass(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // KEY GENERATION
    // ============================================================

    private Object generateKey(String expression,
                               Method method,
                               Object[] args) {
        if (expression != null && !expression.isBlank()) {
            EvaluationContext ctx = new StandardEvaluationContext();
            // Expose arguments as arg0, arg1, ...
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable("arg" + i, args[i]);
            }
            // Also expose method parameters by name if you have parameter name info; simplified.
            return spel.parseExpression(expression).getValue(ctx);
        } else {
            // Default key: method name + underscore + arguments
            StringBuilder keyBuilder = new StringBuilder(method.getName());
            for (Object arg : args) {
                keyBuilder.append("_").append(arg);
            }
            return keyBuilder.toString();
        }
    }
}