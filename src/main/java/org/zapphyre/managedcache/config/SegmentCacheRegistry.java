package org.zapphyre.managedcache.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.zapphyre.managedcache.annotation.CacheManaged;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SegmentCacheRegistry {

    private static class SegmentGraph {
        final Map<Class<?>, List<Class<?>>> direct = new ConcurrentHashMap<>();
        final Map<Class<?>, Set<Class<?>>> closure = new ConcurrentHashMap<>();
        final Set<Class<?>> allClasses = ConcurrentHashMap.newKeySet();
    }

    private final Map<String, SegmentGraph> segments = new ConcurrentHashMap<>();

    public SegmentCacheRegistry(String[] basePackages) {
        buildGraphs(basePackages);
    }

    private void buildGraphs(String[] basePackages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(CacheManaged.class));

        // Temporary: segment name -> list of (class, dependants)
        Map<String, List<ClassInfo>> segmentClasses = new HashMap<>();

        for (String basePackage : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    CacheManaged ann = clazz.getAnnotation(CacheManaged.class);
                    String segment = ann.segment();
                    List<Class<?>> dependants = Arrays.asList(ann.dependants());

                    segmentClasses.computeIfAbsent(segment, k -> new ArrayList<>())
                            .add(new ClassInfo(clazz, dependants));

                } catch (ClassNotFoundException e) {
                    log.error("Failed to load class {}", bd.getBeanClassName(), e);
                }
            }
        }

        // Build each segment's graph
        for (Map.Entry<String, List<ClassInfo>> entry : segmentClasses.entrySet()) {
            String segment = entry.getKey();
            SegmentGraph graph = segments.computeIfAbsent(segment, k -> new SegmentGraph());

            for (ClassInfo info : entry.getValue()) {
                graph.direct.put(info.clazz, info.dependants);
                graph.allClasses.add(info.clazz);
                graph.allClasses.addAll(info.dependants);
            }
        }

        // Compute transitive closures for each segment
        segments.forEach((segment, graph) -> {
            graph.allClasses.forEach(cls -> getAffectedClasses(cls, segment));
            log.info("Segment '{}' initialized with {} classes", segment, graph.allClasses.size());
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

    public Set<Class<?>> getAffectedClasses(Class<?> clazz, String segment) {
        SegmentGraph graph = segments.get(segment);
        if (graph == null) {
            return Collections.singleton(clazz); // fallback: only itself
        }
        return graph.closure.computeIfAbsent(clazz, c -> buildClosure(c, graph));
    }

    private Set<Class<?>> buildClosure(Class<?> clazz, SegmentGraph graph) {
        Set<Class<?>> closure = new LinkedHashSet<>();
        buildClosureRecursive(clazz, graph, closure, new LinkedHashSet<>());
        return Collections.unmodifiableSet(closure);
    }

    private void buildClosureRecursive(Class<?> clazz, SegmentGraph graph,
                                       Set<Class<?>> accumulator, Set<Class<?>> visiting) {
        if (!visiting.add(clazz)) {
            log.warn("Cycle detected involving {} in segment", clazz);
            return;
        }
        accumulator.add(clazz);
        List<Class<?>> dependants = graph.direct.getOrDefault(clazz, Collections.emptyList());
        for (Class<?> dep : dependants) {
            buildClosureRecursive(dep, graph, accumulator, visiting);
        }
        visiting.remove(clazz);
    }

    public boolean isCacheable(Class<?> clazz, String segment) {
        SegmentGraph graph = segments.get(segment);
        return graph != null && graph.allClasses.contains(clazz);
    }
}