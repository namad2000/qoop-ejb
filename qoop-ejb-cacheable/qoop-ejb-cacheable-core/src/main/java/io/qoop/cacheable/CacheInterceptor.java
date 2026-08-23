package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.gson.Gson;
import io.qoop.global.model.LogContent;
import io.qoop.util.EvaluationContextData;
import io.qoop.util.EvaluationService;
import ir.tamin.framework.core.util.Bundle;
import ir.tamin.framework.logging.api.logger.AppLogger;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Interceptor
@Cacheable
@Priority(Interceptor.Priority.APPLICATION)
public class CacheInterceptor {

    @Inject
    private CacheService redisService;

    @Inject
    private LocalCacheManager localCacheManager;

    @Inject
    private AppLogger appLogger;

    @Inject
    private Bundle serviceBundle;

    @Inject
    private EvaluationService evaluationService;

    @Inject
    private ItemWarmingService itemWarmingService;

    @Inject
    private CachePubSubService pubSubService;

    private final Gson gson = new Gson();


    @AroundInvoke
    public Object handleCaching(InvocationContext context) throws Exception {
        Method method = context.getMethod();

        CacheEvict cacheEvict = method.getAnnotation(CacheEvict.class);
        if (cacheEvict != null) {
            return handleEviction(context, cacheEvict);
        }

        Cacheable cacheable = method.getAnnotation(Cacheable.class);
        if (cacheable == null) {
            return context.proceed();
        }

        return processCacheable(context, cacheable);
    }

    // ==========================================
    // Caching Processing Methods
    // ==========================================

    private Object processCacheable(InvocationContext context, Cacheable cacheable) throws Exception {
        String system = resolveSystem(cacheable.system());

        EvaluationContextData evalData = evaluationService.createEvaluationContext(context);

        if (!evaluationService.evaluateCondition(cacheable.condition(), evalData.context)) {
            return context.proceed();
        }

        String cacheKey = evaluationService.evaluateKey(cacheable.key(), evalData);
        Method method = context.getMethod();

        // 1. Try to read from cache (L1 or L2)
        Object cachedResult = findInCache(cacheable, system, cacheKey, method.getGenericReturnType());
        if (cachedResult != null) {
            return cachedResult;
        }

        // 2. Execute original method
        Object result = context.proceed();

        // 3. Check unless condition and save to cache if valid
        if (evaluationService.evaluateUnless(cacheable.unless(), evalData.context, result)) {
            return result;
        }

        if (result != null) {
            writeToCache(cacheable, evalData, system, cacheKey, result);
        }

        return result;
    }

    private Object findInCache(Cacheable cacheable, String system, String cacheKey, Type returnType) {
        for (String cacheName : cacheable.cacheNames()) {
            String fullKey = buildFullKey(system, cacheName, cacheKey);

            // Check L1 Cache
            Object l1Result = readFromL1Cache(cacheable, cacheName, fullKey);
            if (l1Result != null) {
                return l1Result;
            }

            // Check Redis Cache
            Object redisResult = readFromRedisCache(cacheable, cacheName, fullKey, returnType);
            if (redisResult != null) {
                return redisResult;
            }
        }
        return null;
    }

    private Object readFromL1Cache(Cacheable cacheable, String cacheName, String fullKey) {
        if (!cacheable.useLocalCache()) {
            return null;
        }
        Cache<String, Object> l1Cache = getL1Cache(cacheable, cacheName);
        return (l1Cache != null) ? l1Cache.getIfPresent(fullKey) : null;
    }

    private Object readFromRedisCache(Cacheable cacheable, String cacheName, String fullKey, Type returnType) {
        try {
            String cachedJson = redisService.get(fullKey);
            if (cachedJson != null) {
                Object deserialized = gson.fromJson(cachedJson, returnType);
                if (deserialized != null) {
                    populateL1CacheIfEnabled(cacheable, cacheName, fullKey, deserialized);
                    return deserialized;
                }
            }
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Cache read error: ", e.getMessage()), "handleCaching");
        }
        return null;
    }

    private void writeToCache(Cacheable cacheable, EvaluationContextData evalData, String system, String cacheKey, Object result) {
        try {
            String jsonResult = gson.toJson(result);

            for (String cacheName : cacheable.cacheNames()) {
                String fullKey = buildFullKey(system, cacheName, cacheKey);
                redisService.set(fullKey, jsonResult, cacheable.ttlSeconds());
                populateL1CacheIfEnabled(cacheable, cacheName, fullKey, result);
            }

            if (cacheable.enableItemWarming()) {
                itemWarmingService.processItemWarming(cacheable, evalData, result, system);
            }
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Cache write error: ", e.getMessage()), "handleCaching");
        }
    }

    private void populateL1CacheIfEnabled(Cacheable cacheable, String cacheName, String fullKey, Object value) {
        if (cacheable.useLocalCache()) {
            Cache<String, Object> l1Cache = getL1Cache(cacheable, cacheName);
            if (l1Cache != null) {
                l1Cache.put(fullKey, value);
            }
        }
    }

    private Cache<String, Object> getL1Cache(Cacheable cacheable, String cacheName) {
        return localCacheManager.getOrCreateCache(
                cacheName,
                cacheable.localMaximumSize(),
                cacheable.localExpireAfterWriteSeconds()
        );
    }

    // ==========================================
    // Eviction Processing Methods
    // ==========================================

    private Object handleEviction(InvocationContext context, CacheEvict cacheEvict) throws Exception {
        EvaluationContextData evalData = evaluationService.createEvaluationContext(context);

        if (!evaluationService.evaluateCondition(cacheEvict.condition(), evalData.context)) {
            return context.proceed();
        }

        String system = resolveSystem(cacheEvict.system());

        if (cacheEvict.beforeInvocation()) {
            executeEviction(cacheEvict, evalData, system);
        }

        Object result = context.proceed();

        if (!cacheEvict.beforeInvocation()) {
            executeEviction(cacheEvict, evalData, system);
        }

        return result;
    }

    private void executeEviction(CacheEvict cacheEvict, EvaluationContextData evalData, String system) {
        try {
            for (String cacheName : cacheEvict.cacheNames()) {
                if (cacheEvict.allEntries()) {
                    evictAllEntries(cacheName, system);
                } else {
                    evictSpecificEntries(cacheEvict, evalData, cacheName, system);
                }
            }
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Cache eviction error: ", e.getMessage()), "performEviction");
        }
    }

    private void evictAllEntries(String cacheName, String system) {
        String pattern = system + ":" + cacheName + ":*";
        redisService.delByPattern(pattern);
        localCacheManager.invalidateAll(cacheName);
        pubSubService.publishEviction(cacheName + ":*");
    }

    private void evictSpecificEntries(CacheEvict cacheEvict, EvaluationContextData evalData, String cacheName, String system) {
        List<String> keysToEvict = resolveKeysToEvict(cacheEvict, evalData);

        for (String key : keysToEvict) {
            String fullKey = buildFullKey(system, cacheName, key);
            redisService.del(fullKey);

            Cache<String, Object> l1Cache = localCacheManager.getOrCreateCache(cacheName, 1000, 3600);
            if (l1Cache != null) {
                l1Cache.invalidate(fullKey);
            }

            pubSubService.publishEviction(cacheName + ":" + fullKey);
        }
    }

    private List<String> resolveKeysToEvict(CacheEvict cacheEvict, EvaluationContextData evalData) {
        List<String> keysToEvict = new ArrayList<>();
        if (cacheEvict.keys().length > 0) {
            for (String expr : cacheEvict.keys()) {
                keysToEvict.add(evaluationService.evaluateKey(expr, evalData));
            }
        } else {
            keysToEvict.add(evaluationService.evaluateKey(cacheEvict.key(), evalData));
        }
        return keysToEvict;
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private String buildFullKey(String system, String cacheName, String key) {
        return system + ":" + cacheName + ":" + key;
    }

    private String resolveSystem(String annotationSystem) {
        if (!evaluationService.isEmpty(annotationSystem)) {
            return annotationSystem;
        }

        try {
            String bundleSys = serviceBundle.getProperty("cache.system.name");
            if (!evaluationService.isEmpty(bundleSys)) {
                return bundleSys;
            }
        } catch (Exception ignored) {
        }

        String propSys = System.getProperty("cache.system.name");
        if (!evaluationService.isEmpty(propSys)) {
            return propSys;
        }

        throw new IllegalStateException("System name could not be resolved for caching.");
    }
}