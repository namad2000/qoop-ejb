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
public @interface CacheEvict {
    @Nonbinding
    String system() default "";               // System/subsystem identifier to route eviction to the correct namespace

    @Nonbinding
    String[] cacheNames() default {};              // Target cache names or categories to evict entries from

    @Nonbinding
    String key() default "";                  // Single SpEL expression to target a specific key for eviction

    @Nonbinding
    String[] keys() default {};               // Array of SpEL expressions to evict multiple specific keys simultaneously

    @Nonbinding
    String condition() default "";            // SpEL expression evaluated to determine whether eviction should execute

    @Nonbinding
    boolean allEntries() default false;       // Flushes all keys matching the system and cache pattern if set to true

    @Nonbinding
    boolean beforeInvocation() default false; // Triggers eviction before method execution instead of after completion

    // --- L1 Local Cache (Caffeine) Attributes ---
    @Nonbinding
    boolean useLocalCache() default true;       // Flag to enable L1 local cache eviction
}