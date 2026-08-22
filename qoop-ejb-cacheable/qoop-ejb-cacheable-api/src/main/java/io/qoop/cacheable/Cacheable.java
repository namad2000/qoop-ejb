package io.qoop.cacheable;

import javax.enterprise.util.Nonbinding;
import javax.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Cacheable {

    @Nonbinding
    String system() default "";

    @Nonbinding
    String[] cacheNames() default {};

    @Nonbinding
    String key() default "";

    @Nonbinding
    String condition() default "";

    @Nonbinding
    String unless() default "";

    @Nonbinding
    long ttlSeconds() default 86400;

    // --- Granular & Multi-Level Cache Attributes ---
    @Nonbinding
    boolean enableGranular() default false;

    @Nonbinding
    String[] granularCacheNames() default {};

    @Nonbinding
    String granularKey() default "";

    @Nonbinding
    String granularItems() default "";

    @Nonbinding
    long granularTtlSeconds() default 86400;

    // Caffeine L1 Configuration
    @Nonbinding
    boolean useLocalCache() default true;

    @Nonbinding
    long localMaximumSize() default 1000;

    @Nonbinding
    long localExpireAfterWriteSeconds() default 3600;
}