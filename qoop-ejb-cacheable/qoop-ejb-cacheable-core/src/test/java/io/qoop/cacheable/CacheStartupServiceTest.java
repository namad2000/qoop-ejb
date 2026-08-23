package io.qoop.cacheable;

import io.qoop.global.model.LogContent;
import ir.tamin.framework.logging.api.logger.AppLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheStartupService Unit Tests")
class CacheStartupServiceTest {

    @InjectMocks
    private CacheStartupService cacheStartupService;

    @Mock
    private AppLogger appLogger;

    @Mock
    private BeanManager beanManager;

    @Mock
    private Bean<Object> bean;

    @Mock
    private CreationalContext<Object> creationalContext;

    public static class SampleBeanClass {
        private static boolean startupMethodExecuted = false;
        private static boolean nonStartupMethodExecuted = false;

        @Cacheable(startup = true)
        public void warmupMethod() {
            startupMethodExecuted = true;
        }

        @Cacheable(startup = false)
        public void normalCacheableMethod() {
            nonStartupMethodExecuted = true;
        }

        public void regularMethod() {
        }
    }

    public static class ParameterizedBeanClass {
        @Cacheable(startup = true)
        public void warmupMethodWithParam(String param) {
        }
    }

    public static class ThrowingBeanClass {
        @Cacheable(startup = true)
        public void failingWarmupMethod() {
            throw new RuntimeException("Warmup execution error");
        }
    }

    @BeforeEach
    void setUp() {
        SampleBeanClass.startupMethodExecuted = false;
        SampleBeanClass.nonStartupMethodExecuted = false;
    }

    @Nested
    @DisplayName("Warmup Cache On Startup Execution Tests")
    class WarmupCacheOnStartupTests {

        @Test
        @DisplayName("Should invoke startup methods via CDI proxy reference and release context if Dependent scoped")
        void warmupCacheOnStartup_WithValidStartupMethod_ShouldInvokeMethodAndReleaseContext() {
            Set<Bean<?>> beans = new HashSet<Bean<?>>(Collections.singletonList(bean));
            when(beanManager.getBeans(Object.class)).thenReturn(beans);

            Class<?> beanClass = SampleBeanClass.class;
            when(bean.getBeanClass()).thenReturn((Class) beanClass);
            when(bean.getScope()).thenReturn((Class) Dependent.class);
            when(beanManager.createCreationalContext(bean)).thenReturn((CreationalContext) creationalContext);
            when(beanManager.getReference(bean, beanClass, creationalContext)).thenReturn(new SampleBeanClass());

            cacheStartupService.warmupCacheOnStartup();

            assertTrue(SampleBeanClass.startupMethodExecuted);
            assertFalse(SampleBeanClass.nonStartupMethodExecuted);
            verify(creationalContext).release();
            verify(appLogger, never()).errorLog(any(LogContent.class), any(String.class));
        }

        @Test
        @DisplayName("Should skip method and log warning when startup method requires parameters")
        void warmupCacheOnStartup_WithParameterizedMethod_ShouldSkipAndLogWarning() {
            Set<Bean<?>> beans = new HashSet<Bean<?>>(Collections.singletonList(bean));
            when(beanManager.getBeans(Object.class)).thenReturn(beans);

            Class<?> beanClass = ParameterizedBeanClass.class;
            when(bean.getBeanClass()).thenReturn((Class) beanClass);

            cacheStartupService.warmupCacheOnStartup();

            verify(beanManager, never()).createCreationalContext(any());
            verify(appLogger).errorLog(any(LogContent.class), eq("invokeStartupMethod"));
        }

        @Test
        @DisplayName("Should skip releasing context if bean scope is not Dependent")
        void warmupCacheOnStartup_WithNonDependentScope_ShouldNotReleaseContext() {
            Set<Bean<?>> beans = new HashSet<Bean<?>>(Collections.singletonList(bean));
            when(beanManager.getBeans(Object.class)).thenReturn(beans);

            Class<?> beanClass = SampleBeanClass.class;
            when(bean.getBeanClass()).thenReturn((Class) beanClass);
            when(bean.getScope()).thenReturn((Class) RequestScoped.class);
            when(beanManager.createCreationalContext(bean)).thenReturn((CreationalContext) creationalContext);
            when(beanManager.getReference(bean, beanClass, creationalContext)).thenReturn(new SampleBeanClass());

            cacheStartupService.warmupCacheOnStartup();

            assertTrue(SampleBeanClass.startupMethodExecuted);
            verify(creationalContext, never()).release();
        }

        @Test
        @DisplayName("Should skip processing when bean class is null")
        void warmupCacheOnStartup_WhenBeanClassIsNull_ShouldSkip() {
            Set<Bean<?>> beans = new HashSet<Bean<?>>(Collections.singletonList(bean));
            when(beanManager.getBeans(Object.class)).thenReturn(beans);
            when(bean.getBeanClass()).thenReturn(null);

            cacheStartupService.warmupCacheOnStartup();

            verify(beanManager, never()).createCreationalContext(any());
            verify(appLogger, never()).errorLog(any(LogContent.class), any(String.class));
        }

        @Test
        @DisplayName("Should log error when invokeStartupMethod throws an exception during proxy method execution")
        void warmupCacheOnStartup_WhenMethodInvocationFails_ShouldLogSpecificError() {
            Set<Bean<?>> beans = new HashSet<Bean<?>>(Collections.singletonList(bean));
            when(beanManager.getBeans(Object.class)).thenReturn(beans);

            Class<?> beanClass = ThrowingBeanClass.class;
            when(bean.getBeanClass()).thenReturn((Class) beanClass);
            when(bean.getScope()).thenReturn(null);
            when(beanManager.createCreationalContext(bean)).thenReturn((CreationalContext) creationalContext);
            when(beanManager.getReference(bean, beanClass, creationalContext)).thenReturn(new ThrowingBeanClass());

            cacheStartupService.warmupCacheOnStartup();

            verify(appLogger).errorLog(any(LogContent.class), eq("invokeStartupMethod"));
        }

        @Test
        @DisplayName("Should log error when outer warmup loop encounters an exception")
        void warmupCacheOnStartup_WhenOuterLoopThrowsException_ShouldLogGlobalError() {
            when(beanManager.getBeans(Object.class)).thenThrow(new RuntimeException("CDI BeanManager failure"));

            cacheStartupService.warmupCacheOnStartup();

            verify(appLogger).errorLog(any(LogContent.class), eq("warmupCacheOnStartup"));
        }
    }
}