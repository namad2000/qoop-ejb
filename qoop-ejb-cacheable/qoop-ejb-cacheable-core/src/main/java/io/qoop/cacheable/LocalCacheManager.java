package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class LocalCacheManager {

    private static final long DEFAULT_MAXIMUM_SIZE = 1000L;
    private static final long DEFAULT_EXPIRE_SECONDS = 3600L;

    private final Map<String, Cache<String, Object>> caches = new ConcurrentHashMap<>();

    public Cache<String, Object> getOrCreateCache(String cacheName, long maximumSize, long expireAfterWriteSeconds) {
        if (isBlank(cacheName)) {
            return null;
        }

        return caches.computeIfAbsent(cacheName, name -> buildCache(maximumSize, expireAfterWriteSeconds));
    }

    private Cache<String, Object> buildCache(long maximumSize, long expireAfterWriteSeconds) {
        long maxSize = maximumSize > 0 ? maximumSize : DEFAULT_MAXIMUM_SIZE;
        long expireSeconds = expireAfterWriteSeconds > 0 ? expireAfterWriteSeconds : DEFAULT_EXPIRE_SECONDS;

        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    public Cache<String, Object> getCache(String cacheName) {
        if (isBlank(cacheName)) {
            return null;
        }
        return caches.get(cacheName);
    }

    public void invalidate(String cacheName, String key) {
        if (isBlank(cacheName) || isBlank(key)) {
            return;
        }
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidate(key);
        }
    }

    public void invalidateAll(String cacheName) {
        if (isBlank(cacheName)) {
            return;
        }
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    public void clearAllCaches() {
        caches.values().forEach(Cache::invalidateAll);
        caches.clear();
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}