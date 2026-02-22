package org.zapphyre.managedcache.config;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.zapphyre.managedcache.annotation.EnableManagedDomainCache;
import org.zapphyre.managedcache.aop.ManagedCacheAspect;

import java.util.Map;

public class ManagedDomainCacheRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attributes = importingClassMetadata
                .getAnnotationAttributes(EnableManagedDomainCache.class.getName());

        if (attributes == null) return;

        String[] basePackages = (String[]) attributes.get("basePackages");

        // Register SegmentCacheRegistry bean with base packages
        if (!registry.containsBeanDefinition("segmentCacheRegistry")) {
            GenericBeanDefinition registryDef = new GenericBeanDefinition();
            registryDef.setBeanClass(SegmentCacheRegistry.class);
            registryDef.getConstructorArgumentValues().addGenericArgumentValue(basePackages);
            registryDef.setAutowireCandidate(true);
            registry.registerBeanDefinition("segmentCacheRegistry", registryDef);
        }

        // Register Aspect bean
        if (!registry.containsBeanDefinition("managedCacheAspect")) {
            GenericBeanDefinition aspectDef = new GenericBeanDefinition();
            aspectDef.setBeanClass(ManagedCacheAspect.class);
            aspectDef.setAutowireCandidate(true);
            registry.registerBeanDefinition("managedCacheAspect", aspectDef);
        }
    }
}