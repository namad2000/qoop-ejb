package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.gson.Gson;
import io.qoop.global.model.LogContent;
import io.qoop.util.EvaluationService;
import io.qoop.util.EvaluationContextData;
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

    private final CacheService redisService;
    private final LocalCacheManager localCacheManager;
    private final AppLogger appLogger;
    private final Bundle serviceBundle;
    private final EvaluationService evaluationService;
    private final ItemWarmingService itemWarmingService;
    private final CachePubSubService pubSubService;
    private final Gson gson = new Gson();

    @Inject
    public CacheInterceptor(CacheService redisService,
                            LocalCacheManager localCacheManager,
                            AppLogger appLogger,
                            Bundle serviceBundle,
                            EvaluationService evaluationService,
                            ItemWarmingService itemWarmingService,
                            CachePubSubService pubSubService) {
        this.redisService = redisService;
        this.localCacheManager = localCacheManager;
        this.appLogger = appLogger;
        this.serviceBundle = serviceBundle;
        this.evaluationService = evaluationService;
        this.itemWarmingService = itemWarmingService;
        this.pubSubService = pubSubService;
    }

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

        String system = resolveSystem(cacheable.system());
        EvaluationContextData evalData = evaluationService.createEvaluationContext(context);

        if (!evaluationService.evaluateCondition(cacheable.condition(), evalData.context)) {
            return context.proceed();
        }

        String cacheKey = evaluationService.evaluateKey(cacheable.key(), evalData);
        String[] cacheNames = cacheable.cacheNames();
        Type returnType = method.getGenericReturnType();

        // Check L1 & Redis across all defined cacheNames
        for (String cacheName : cacheNames) {
            String fullKey = system + ":" + cacheName + ":" + cacheKey;

            Cache<String, Object> l1Cache = cacheable.useLocalCache() ?
                    localCacheManager.getOrCreateCache(cacheName, cacheable.localMaximumSize(), cacheable.localExpireAfterWriteSeconds()) : null;

            if (l1Cache != null) {
                Object l1Result = l1Cache.getIfPresent(fullKey);
                if (l1Result != null) {
                    return l1Result;
                }
            }

            try {
                String cachedJson = redisService.get(fullKey);
                if (cachedJson != null) {
                    Object deserialized = gson.fromJson(cachedJson, returnType);
                    if (deserialized != null) {
                        if (l1Cache != null) {
                            l1Cache.put(fullKey, deserialized);
                        }
                        return deserialized;
                    }
                }
            } catch (Exception e) {
                appLogger.errorLog(new LogContent("Cache read error: ", e.getMessage()), "handleCaching");
            }
        }

        Object result = context.proceed();

        if (evaluationService.evaluateUnless(cacheable.unless(), evalData.context, result)) {
            return result;
        }

        if (result != null) {
            try {
                String jsonResult = gson.toJson(result);
                // Save to all defined cacheNames
                for (String cacheName : cacheNames) {
                    String fullKey = system + ":" + cacheName + ":" + cacheKey;
                    redisService.set(fullKey, jsonResult, cacheable.ttlSeconds());

                    if (cacheable.useLocalCache()) {
                        Cache<String, Object> l1Cache = localCacheManager.getOrCreateCache(cacheName, cacheable.localMaximumSize(), cacheable.localExpireAfterWriteSeconds());
                        if (l1Cache != null) {
                            l1Cache.put(fullKey, result);
                        }
                    }
                }

                if (cacheable.enableItemWarming()) {
                    itemWarmingService.processItemWarming(cacheable, evalData, result, system);
                }
            } catch (Exception e) {
                appLogger.errorLog(new LogContent("Cache write error: ", e.getMessage()), "handleCaching");
            }
        }

        return result;
    }

    private Object handleEviction(InvocationContext context, CacheEvict cacheEvict) throws Exception {
        Method method = context.getMethod();
        String system = resolveSystem(cacheEvict.system());
        EvaluationContextData evalData = evaluationService.createEvaluationContext(context);

        if (!evaluationService.evaluateCondition(cacheEvict.condition(), evalData.context)) {
            return context.proceed();
        }

        if (cacheEvict.beforeInvocation()) {
            executeEviction(cacheEvict, evalData, system, method);
        }

        Object result = context.proceed();

        if (!cacheEvict.beforeInvocation()) {
            executeEviction(cacheEvict, evalData, system, method);
        }

        return result;
    }

    private void executeEviction(CacheEvict cacheEvict, EvaluationContextData evalData, String system, Method method) {
        try {
            String[] cacheNames = cacheEvict.cacheNames();

            for (String cacheName : cacheNames) {
                if (cacheEvict.allEntries()) {
                    String pattern = system + ":" + cacheName + ":*";
                    redisService.delByPattern(pattern);
                    localCacheManager.invalidateAll(cacheName);
                    pubSubService.publishEviction(cacheName + ":*");
                    continue;
                }

                List<String> keysToEvict = new ArrayList<>();
                if (cacheEvict.keys().length > 0) {
                    for (String expr : cacheEvict.keys()) {
                        keysToEvict.add(evaluationService.evaluateKey(expr, evalData));
                    }
                } else {
                    keysToEvict.add(evaluationService.evaluateKey(cacheEvict.key(), evalData));
                }

                for (String key : keysToEvict) {
                    String fullKey = system + ":" + cacheName + ":" + key;
                    redisService.del(fullKey);
                    Cache<String, Object> l1Cache = localCacheManager.getOrCreateCache(cacheName, 1000, 3600);
                    if (l1Cache != null) {
                        l1Cache.invalidate(fullKey);
                    }
                    pubSubService.publishEviction(cacheName + ":" + fullKey);
                }
            }
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Cache eviction error: ", e.getMessage()), "performEviction");
        }
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