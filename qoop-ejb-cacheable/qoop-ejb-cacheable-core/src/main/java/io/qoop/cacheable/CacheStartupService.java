package io.qoop.cacheable;

import io.qoop.global.model.LogContent;
import ir.tamin.framework.logging.api.logger.AppLogger;
import ir.tamin.framework.logging.api.qualifier.ApplicationLogger;

import javax.annotation.PostConstruct;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Set;

@Startup
@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class CacheStartupService {

    @Inject
    @ApplicationLogger
    private AppLogger appLogger;

    @Inject
    private BeanManager beanManager;

    @PostConstruct
    public void warmupCacheOnStartup() {
        try {
            Set<Bean<?>> beans = beanManager.getBeans(Object.class);
            for (Bean<?> bean : beans) {
                Class<?> beanClass = bean.getBeanClass();
                if (beanClass == null) {
                    continue;
                }

                for (Method method : beanClass.getDeclaredMethods()) {
                    Cacheable cacheable = method.getAnnotation(Cacheable.class);
                    if (cacheable != null && cacheable.startup()) {
                        invokeStartupMethod(bean, method);
                    }
                }
            }
        } catch (Exception e) {
            appLogger.errorLog(
                    new LogContent("Error during cache startup warmup", e.getMessage()),
                    "warmupCacheOnStartup"
            );
        }
    }

    private void invokeStartupMethod(Bean<?> bean, Method method) {
        if (method.getParameterCount() > 0) {
            appLogger.errorLog(
                    new LogContent("Startup warmup skipped. No-arg method required for: " + method.getName(), ""),
                    "invokeStartupMethod"
            );
            return;
        }

        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)) {
            appLogger.errorLog(
                    new LogContent("Startup warmup skipped. Public/protected non-static method required for: " + method.getName(), ""),
                    "invokeStartupMethod"
            );
            return;
        }

        CreationalContext<?> ctx = null;
        try {
            ctx = beanManager.createCreationalContext(bean);
            Type requiredType = selectRequiredType(bean);

            Object proxyInstance = beanManager.getReference(bean, requiredType, ctx);

            Method targetMethod;
            try {
                targetMethod = proxyInstance.getClass().getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                targetMethod = method;
                targetMethod.setAccessible(true);
            }

            targetMethod.invoke(proxyInstance);

        } catch (Exception e) {
            appLogger.errorLog(
                    new LogContent("Failed to warmup startup cache for method: " + method.getName(), e.getMessage()),
                    "invokeStartupMethod"
            );
        } finally {
            if (ctx != null && isDependentScoped(bean)) {
                ctx.release();
            }
        }
    }

    private boolean isDependentScoped(Bean<?> bean) {
        Class<? extends Annotation> scope = bean.getScope();
        return scope != null && Dependent.class.isAssignableFrom(scope);
    }

    private Type selectRequiredType(Bean<?> bean) {
        if (bean.getTypes() != null) {
            for (Type type : bean.getTypes()) {
                if (type instanceof Class && ((Class<?>) type).isInterface()) {
                    return type;
                }
            }
        }
        return bean.getBeanClass() != null ? bean.getBeanClass() : Object.class;
    }
}