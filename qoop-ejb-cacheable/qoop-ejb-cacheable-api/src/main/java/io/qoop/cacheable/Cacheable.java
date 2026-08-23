package io.qoop.cacheable;

import javax.enterprise.util.Nonbinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@javax.interceptor.InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Cacheable {
    @Nonbinding
    String system() default "";        // System/subsystem identifier to prevent key collisions across modules

    @Nonbinding
    String[] cacheNames() default {};       // Target cache names or categories (e.g., "users", "orders")

    @Nonbinding
    String key() default "";           // SpEL expression to dynamically generate a single cache key

    @Nonbinding
    String condition() default "";     // SpEL expression evaluated before execution; caching runs only if true

    @Nonbinding
    String unless() default "";        // SpEL expression evaluated after execution; prevents caching if true (uses #result)

    @Nonbinding
    long ttlSeconds() default 86400;    // Time-To-Live duration for cached entries in seconds (used for both main and item entries)

    // --- Startup Caching Attribute ---
    @Nonbinding
    boolean startup() default false; // Flag to trigger caching on application startup

    // --- Item-level Caching (Warmup) Attributes ---
    @Nonbinding
    boolean enableItemWarming() default false;     // Flag to enable individual item warming for collection elements

    @Nonbinding
    String[] itemCacheNames() default {};   // Target cache names for individual item entries (matches single-fetch methods)

    @Nonbinding
    String itemKey() default "";            // SpEL expression to generate a unique key for each item (e.g., #item.id)

    @Nonbinding
    String itemSource() default "";          // SpEL expression to extract items from parameters (useful for void or custom bulk methods)

    // --- L1 Local Cache (Caffeine) Attributes ---
    @Nonbinding
    boolean useLocalCache() default true;       // Flag to enable L1 local cache (Caffeine)

    @Nonbinding
    long localMaximumSize() default 1000;      // Maximum size for L1 local cache

    @Nonbinding
    long localExpireAfterWriteSeconds() default 3600; // Expire after write duration for L1 local cache in seconds
}