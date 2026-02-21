package org.zapphyre.managedcache.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.zapphyre.managedcache.annotation.CacheManaged;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DomainCacheRegistry {

    // Inner class representing a single domain's graph
    private static class DomainGraph {
        final Map<Class<?>, List<Class<?>>> direct = new ConcurrentHashMap<>();
        final Map<Class<?>, Set<Class<?>>> closure = new ConcurrentHashMap<>();
        final Set<Class<?>> allClasses = ConcurrentHashMap.newKeySet();
    }

    private final Map<String, DomainGraph> domains = new ConcurrentHashMap<>();

    public DomainCacheRegistry(String[] basePackages) {
        buildGraphs(basePackages);
    }

    private void buildGraphs(String[] basePackages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(CacheManaged.class));

        // First pass: collect all annotated classes and their dependants per domain
        for (String basePackage : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    CacheManaged ann = clazz.getAnnotation(CacheManaged.class);
                    String domain = ann.domain();
                    List<Class<?>> dependants = Arrays.asList(ann.dependants());

                    DomainGraph graph = domains.computeIfAbsent(domain, k -> new DomainGraph());
                    graph.direct.put(clazz, dependants);
                    graph.allClasses.add(clazz);
                    graph.allClasses.addAll(dependants);
                } catch (ClassNotFoundException e) {
                    log.error("Failed to load class {}", bd.getBeanClassName(), e);
                }
            }
        }

        // Second pass: compute transitive closures for each domain
        domains.forEach((domain, graph) -> {
            graph.allClasses.forEach(cls -> getAffectedClasses(cls, domain));
            log.info("Domain '{}' initialized with {} classes", domain, graph.allClasses.size());
        });
    }

    public Set<Class<?>> getAffectedClasses(Class<?> clazz, String domain) {
        DomainGraph graph = domains.get(domain);
        if (graph == null) {
            return Collections.singleton(clazz); // fallback: only itself
        }
        return graph.closure.computeIfAbsent(clazz, c -> buildClosure(c, graph));
    }

    private Set<Class<?>> buildClosure(Class<?> clazz, DomainGraph graph) {
        Set<Class<?>> closure = new LinkedHashSet<>();
        buildClosureRecursive(clazz, graph, closure, new LinkedHashSet<>());
        return Collections.unmodifiableSet(closure);
    }

    private void buildClosureRecursive(Class<?> clazz, DomainGraph graph,
                                       Set<Class<?>> accumulator, Set<Class<?>> visiting) {
        if (!visiting.add(clazz)) {
            log.warn("Cycle detected involving {} in domain", clazz);
            return;
        }
        accumulator.add(clazz);
        List<Class<?>> dependants = graph.direct.getOrDefault(clazz, Collections.emptyList());
        for (Class<?> dep : dependants) {
            buildClosureRecursive(dep, graph, accumulator, visiting);
        }
        visiting.remove(clazz);
    }

    public boolean isCacheable(Class<?> clazz, String domain) {
        DomainGraph graph = domains.get(domain);
        return graph != null && graph.allClasses.contains(clazz);
    }
}