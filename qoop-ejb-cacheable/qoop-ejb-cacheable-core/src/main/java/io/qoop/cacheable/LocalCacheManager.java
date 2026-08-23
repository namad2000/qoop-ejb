package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class LocalCacheManager {

    private final Map<String, Cache<String, Object>> caches = new ConcurrentHashMap<>();

    public Cache<String, Object> getOrCreateCache(String cacheName, long maximumSize, long expireAfterWriteSeconds) {
        return caches.computeIfAbsent(cacheName, name ->
                Caffeine.newBuilder()
                        .maximumSize(maximumSize > 0 ? maximumSize : 1000)
                        .expireAfterWrite(expireAfterWriteSeconds > 0 ? expireAfterWriteSeconds : 3600, TimeUnit.SECONDS)
                        .recordStats()
                        .build()
        );
    }

    public void invalidate(String cacheName, String key) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidate(key);
        }
    }

    public void invalidateAll(String cacheName) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateAll();
        }
    }
}