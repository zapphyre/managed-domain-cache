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

    private static class DomainGraph {
        final Map<Class<?>, List<Class<?>>> direct = new ConcurrentHashMap<>();
        final Map<Class<?>, Set<Class<?>>> closure = new ConcurrentHashMap<>();
        final Set<Class<?>> allClasses = ConcurrentHashMap.newKeySet();
    }

    private final Map<String, DomainGraph> domains = new ConcurrentHashMap<>();

    public DomainCacheRegistry(Map<String, String[]> domainPackages) {
        buildGraphs(domainPackages);
    }

    private void buildGraphs(Map<String, String[]> domainPackages) {
        // Collect all packages to scan
        Set<String> allPackages = new LinkedHashSet<>();
        for (String[] packages : domainPackages.values()) {
            allPackages.addAll(Arrays.asList(packages));
        }

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(CacheManaged.class));

        // Temporary storage: domain -> list of classes with their dependants
        Map<String, List<ClassInfo>> domainClasses = new HashMap<>();

        for (String basePackage : allPackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    CacheManaged ann = clazz.getAnnotation(CacheManaged.class);
                    String domain = ann.domain();
                    List<Class<?>> dependants = Arrays.asList(ann.dependants());

                    domainClasses.computeIfAbsent(domain, k -> new ArrayList<>())
                            .add(new ClassInfo(clazz, dependants));

                } catch (ClassNotFoundException e) {
                    log.error("Failed to load class {}", bd.getBeanClassName(), e);
                }
            }
        }

        // Now build each domain's graph
        for (Map.Entry<String, List<ClassInfo>> entry : domainClasses.entrySet()) {
            String domain = entry.getKey();
            DomainGraph graph = domains.computeIfAbsent(domain, k -> new DomainGraph());

            for (ClassInfo info : entry.getValue()) {
                graph.direct.put(info.clazz, info.dependants);
                graph.allClasses.add(info.clazz);
                graph.allClasses.addAll(info.dependants);
            }
        }

        // Compute transitive closures for each domain
        domains.forEach((domain, graph) -> {
            graph.allClasses.forEach(cls -> getAffectedClasses(cls, domain));
            log.info("Domain '{}' initialized with {} classes", domain, graph.allClasses.size());
        });
    }

    private static class ClassInfo {
        final Class<?> clazz;
        final List<Class<?>> dependants;
        ClassInfo(Class<?> clazz, List<Class<?>> dependants) {
            this.clazz = clazz;
            this.dependants = dependants;
        }
    }

    public Set<Class<?>> getAffectedClasses(Class<?> clazz, String domain) {
        DomainGraph graph = domains.get(domain);
        if (graph == null) {
            return Collections.singleton(clazz);
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
            log.warn("Cycle detected involving {}", clazz);
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