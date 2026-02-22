package org.zapphyre.managedcache.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.ResolvableType;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.zapphyre.managedcache.annotation.DomainCache;
import org.zapphyre.managedcache.annotation.DomainCacheEvict;
import org.zapphyre.managedcache.config.SegmentCacheRegistry;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ManagedCacheAspect {

    private final CacheManager cacheManager;
    private final SegmentCacheRegistry segmentRegistry;
    private final SpelExpressionParser spel = new SpelExpressionParser();

    // ============================================================
    // READ: @DomainCache
    // ============================================================

    @Pointcut("@annotation(domainCache)")
    public void readMethod(DomainCache domainCache) {}

    @Around(value = "readMethod(cfg)", argNames = "pjp, cfg")
    public Object handleRead(ProceedingJoinPoint pjp, DomainCache cfg) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Object[] args = pjp.getArgs();
        String segment = cfg.segment();

        Class<?> entityType = resolveEntityType(method);
        if (entityType == null || !segmentRegistry.isCacheable(entityType, segment)) {
            log.debug("Entity type not cacheable in segment '{}' for {}", segment, method.getName());
            return pjp.proceed();
        }

        Object key = generateKey(cfg.key(), method, args);
        if (key == null) {
            log.debug("No cache key for {} in segment '{}'", method.getName(), segment);
            return pjp.proceed();
        }

        Cache cache = cacheManager.getCache(entityType.getName());
        if (cache == null) {
            return pjp.proceed();
        }

        return readThrough(cache, key, entityType, segment, () -> {
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
                               String segment,
                               Supplier<Object> execution) {
        Cache.ValueWrapper wrapper = cache.get(key);
        if (wrapper != null) {
            log.debug("Cache HIT [{}:{}] in segment '{}'", cache.getName(), key, segment);
            return wrapper.get();
        }

        log.debug("Cache MISS [{}:{}] in segment '{}'", cache.getName(), key, segment);

        Object result = execution.get();

        if (result != null) {
            if (result instanceof Collection) {
                Collection<?> collection = (Collection<?>) result;
                if (!collection.isEmpty()) {
                    Class<?> elementType = collection.iterator().next().getClass();
                    if (entityType.isAssignableFrom(elementType) && segmentRegistry.isCacheable(elementType, segment)) {
                        cache.put(key, result);
                        log.debug("Cached collection [{}:{}] in segment '{}'", cache.getName(), key, segment);
                    }
                }
            } else if (entityType.isAssignableFrom(result.getClass()) && segmentRegistry.isCacheable(result.getClass(), segment)) {
                cache.put(key, result);
                log.debug("Cached single result [{}:{}] in segment '{}'", cache.getName(), key, segment);
            }
        }

        return result;
    }

    // ============================================================
    // WRITE: @DomainCacheEvict
    // ============================================================

    @Pointcut("@annotation(evictConfig)")
    public void evictMethod(DomainCacheEvict evictConfig) {}

    @AfterReturning(
            pointcut = "evictMethod(cfg)",
            returning = "result",
            argNames = "jp,cfg,result"
    )
    public void handleEvict(JoinPoint jp,
                            DomainCacheEvict cfg,
                            Object result) {
        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        Object[] args = jp.getArgs();
        String segment = cfg.segment();

        // Determine the entity type to evict
        Class<?> entityType = determineEvictType(cfg, result, args, segment);
        if (entityType == null) {
            log.debug("No cacheable entity found for eviction in segment '{}'", segment);
            return;
        }

        Cache cache = cacheManager.getCache(entityType.getName());
        if (cache == null) {
            log.debug("No cache region found for {}", entityType.getName());
            return;
        }

        performEviction(cache, cfg, method, args, segment, entityType);
    }

    private Class<?> determineEvictType(DomainCacheEvict cfg,
                                        Object result,
                                        Object[] args,
                                        String segment) {
        // 1. Explicit type from annotation (if not Object.class)
        if (cfg.type() != Object.class && segmentRegistry.isCacheable(cfg.type(), segment)) {
            return cfg.type();
        }

        // 2. Infer from result
        Class<?> fromResult = extractEntityType(result);
        if (fromResult != null && segmentRegistry.isCacheable(fromResult, segment)) {
            return fromResult;
        }

        // 3. Infer from method arguments
        for (Object arg : args) {
            Class<?> type = extractEntityType(arg);
            if (type != null && segmentRegistry.isCacheable(type, segment)) {
                return type;
            }
        }

        return null;
    }

    private void performEviction(Cache cache,
                                 DomainCacheEvict cfg,
                                 Method method,
                                 Object[] args,
                                 String segment,
                                 Class<?> entityType) {
        // Named key eviction if specified
        if (!cfg.key().isEmpty()) {
            Object key = generateKey(cfg.key(), method, args);
            if (key != null) {
                cache.evict(key);
                log.debug("Evicted [{}:{}] in segment '{}'", cache.getName(), key, segment);
                if (cfg.atomic()) return; // atomic + named key: stop after named eviction
            }
        }

        // If atomic and no named key, clear entire region for this type
        if (cfg.atomic()) {
            cache.clear();
            log.debug("Atomically cleared region [{}] in segment '{}'", cache.getName(), segment);
            return;
        }

        // Non-atomic: evict entire graph for this entity type
        Set<Class<?>> affected = segmentRegistry.getAffectedClasses(entityType, segment);
        affected.stream()
                .map(Class::getName)
                .map(cacheManager::getCache)
                .filter(Objects::nonNull)
                .forEach(c -> {
                    c.clear();
                    log.debug("Hierarchically cleared region [{}] in segment '{}'", c.getName(), segment);
                });
    }

    // ============================================================
    // Helper Methods (unchanged)
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

    private Object generateKey(String expression,
                               Method method,
                               Object[] args) {
        if (expression != null && !expression.isBlank()) {
            EvaluationContext ctx = new StandardEvaluationContext();
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable("arg" + i, args[i]);
            }
            return spel.parseExpression(expression).getValue(ctx);
        } else {
            StringBuilder keyBuilder = new StringBuilder(method.getName());
            for (Object arg : args) {
                keyBuilder.append("_").append(arg);
            }
            return keyBuilder.toString();
        }
    }
}