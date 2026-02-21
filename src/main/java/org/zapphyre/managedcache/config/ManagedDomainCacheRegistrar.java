package org.zapphyre.managedcache.config;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.zapphyre.managedcache.annotation.EnableManagedDomainCache;
import org.zapphyre.managedcache.aop.ManagedCacheAspect;

import java.util.LinkedHashMap;
import java.util.Map;

public class ManagedDomainCacheRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attributes = importingClassMetadata
                .getAnnotationAttributes(EnableManagedDomainCache.class.getName());

        if (attributes == null) return;

        // Build domain config map
        Map<String, String[]> domainPackages = new LinkedHashMap<>();

        // Check if multiple domains are configured
        Object domainsValue = attributes.get("domains");
        if (domainsValue instanceof EnableManagedDomainCache.DomainConfig[] && ((EnableManagedDomainCache.DomainConfig[]) domainsValue).length > 0) {
            EnableManagedDomainCache.DomainConfig[] domains = (EnableManagedDomainCache.DomainConfig[]) domainsValue;
            for (EnableManagedDomainCache.DomainConfig domain : domains) {
                domainPackages.put(domain.name(), domain.basePackages());
            }
        } else {
            // Fallback to default domain with dtoBasePackages
            String[] basePackages = (String[]) attributes.get("dtoBasePackages");
            if (basePackages.length == 0 && importingClassMetadata instanceof StandardAnnotationMetadata) {
                Class<?> clazz = ((StandardAnnotationMetadata) importingClassMetadata).getIntrospectedClass();
                basePackages = new String[]{ClassUtils.getPackageName(clazz)};
            }
            if (basePackages.length > 0) {
                domainPackages.put("default", basePackages);
            }
        }

        if (domainPackages.isEmpty()) {
            throw new IllegalStateException("No domain packages configured. Please specify dtoBasePackages or domains in @EnableManagedDomainCache.");
        }

        // Register DomainCacheRegistry bean with domain packages map
        if (!registry.containsBeanDefinition("domainCacheRegistry")) {
            GenericBeanDefinition registryDef = new GenericBeanDefinition();
            registryDef.setBeanClass(DomainCacheRegistry.class);
            registryDef.getConstructorArgumentValues().addGenericArgumentValue(domainPackages);
            registryDef.setAutowireCandidate(true);
            registry.registerBeanDefinition("domainCacheRegistry", registryDef);
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