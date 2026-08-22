package io.qoop.cacheable;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import ir.tamin.finance.generalledger.model.LogContent;
import ir.tamin.framework.cdi.util.WebProperties;
import ir.tamin.framework.core.util.Bundle;
import ir.tamin.framework.logging.api.logger.AppLogger;
import ir.tamin.framework.logging.api.qualifier.ApplicationLogger;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import javax.annotation.Priority;
import javax.ejb.EJB;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Interceptor
@Cacheable
@CacheEvict
@Priority(Interceptor.Priority.APPLICATION)
public class CacheInterceptor {

    private static final String PROPERTY_SYSTEM_NAME = "cache.system.name";
    private static final String ENV_SYSTEM_NAME = "CACHE_SYSTEM_NAME";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Inject
    @ApplicationLogger
    private AppLogger appLogger;

    @Inject
    @WebProperties
    private Bundle serviceBundle;

    @EJB
    private CacheService redisService;

    @Inject
    private LocalCacheManager localCacheManager;

    @AroundInvoke
    public Object handleCaching(InvocationContext context) throws Exception {
        Method method = context.getMethod();

        if (method.isAnnotationPresent(Cacheable.class)) {
            return processCacheable(context, method);
        }

        if (method.isAnnotationPresent(CacheEvict.class)) {
            return processCacheEvict(context, method);
        }

        return context.proceed();
    }

    private Object processCacheable(InvocationContext context, Method method) throws Exception {
        Cacheable cacheable = method.getAnnotation(Cacheable.class);
        EvaluationContext evalContext = createEvaluationContext(method, context.getParameters());

        if (!isConditionMet(cacheable.condition(), evalContext)) {
            return context.proceed();
        }

        boolean isVoidMethod = isVoid(method);

        // 1. Try multi-level fetch (Caffeine -> Redis)
        if (!isVoidMethod && hasValidCacheConfig(cacheable)) {
            Object cachedResult = tryFetchFromMultiLevelCache(cacheable, evalContext, context.getParameters(), method);
            if (cachedResult != null) {
                return cachedResult;
            }
        }

        // 2. Proceed with actual method execution
        Object dbResult = context.proceed();

        // 3. Store in multi-level cache
        if (!isVoidMethod && dbResult != null) {
            storeMultiLevelCache(cacheable, evalContext, context.getParameters(), dbResult, method);
        }

        // 4. Perform Granular Cache Warming (L1 + L2)
        if (cacheable.enableGranular()) {
            performGranularWarming(cacheable, evalContext, method, context.getParameters(), dbResult);
        }

        return dbResult;
    }

    private Object processCacheEvict(InvocationContext context, Method method) throws Exception {
        CacheEvict evict = method.getAnnotation(CacheEvict.class);
        EvaluationContext evalContext = createEvaluationContext(method, context.getParameters());

        boolean shouldEvict = isConditionMet(evict.condition(), evalContext);

        if (shouldEvict && evict.beforeInvocation()) {
            executeEviction(evict, evalContext, context.getParameters());
        }

        Object result = context.proceed();

        if (shouldEvict && !evict.beforeInvocation()) {
            executeEviction(evict, evalContext, context.getParameters());
        }

        return result;
    }

    private Object tryFetchFromMultiLevelCache(Cacheable cacheable, EvaluationContext evalContext, Object[] args, Method method) {
        String evaluatedKey = evaluateKey(cacheable.key(), evalContext, args);

        for (String cacheName : cacheable.cacheNames()) {
            String fullKey = buildFullKey(cacheable.system(), cacheName, evaluatedKey);

            // 1. Check L1 (Caffeine)
            if (cacheable.useLocalCache()) {
                Cache<String, Object> localCache = localCacheManager.getOrCreateCache(
                        cacheName, cacheable.localMaximumSize(), cacheable.localExpireAfterWriteSeconds()
                );
                Object localVal = localCache.getIfPresent(fullKey);
                if (localVal != null) {
                    return localVal;
                }
            }

            // 2. Check L2 (Redis)
            try {
                String cachedData = redisService.get(fullKey);
                if (cachedData != null) {
                    Object result = deserialize(cachedData, method);
                    if (result != null) {
                        if (cacheable.useLocalCache()) {
                            Cache<String, Object> localCache = localCacheManager.getOrCreateCache(
                                    cacheName, cacheable.localMaximumSize(), cacheable.localExpireAfterWriteSeconds()
                            );
                            localCache.put(fullKey, result);
                        }
                        return result;
                    }
                }
            } catch (Exception e) {
                appLogger.errorLog(new LogContent("Multi-level cache read bypass: " + fullKey, e.getMessage()), "handleCaching");
            }
        }
        return null;
    }

    private void storeMultiLevelCache(Cacheable cacheable, EvaluationContext evalContext, Object[] args, Object dbResult, Method method) {
        evalContext.setVariable("result", dbResult);

        if (!cacheable.unless().isEmpty()) {
            boolean isUnless = Boolean.TRUE.equals(spelParser.parseExpression(cacheable.unless()).getValue(evalContext, Boolean.class));
            if (isUnless) {
                return;
            }
        }

        String evaluatedKey = evaluateKey(cacheable.key(), evalContext, args);

        for (String cacheName : cacheable.cacheNames()) {
            String fullKey = buildFullKey(cacheable.system(), cacheName, evaluatedKey);

            // Store in L1 (Caffeine)
            if (cacheable.useLocalCache()) {
                Cache<String, Object> localCache = localCacheManager.getOrCreateCache(
                        cacheName, cacheable.localMaximumSize(), cacheable.localExpireAfterWriteSeconds()
                );
                localCache.put(fullKey, dbResult);
            }

            // Store in L2 (Redis)
            try {
                redisService.set(fullKey, serialize(dbResult), cacheable.ttlSeconds());
            } catch (Exception e) {
                appLogger.errorLog(new LogContent("Cache write fail: " + fullKey, e.getMessage()), "handleCaching");
            }
        }
    }

    private void performGranularWarming(Cacheable cacheable, EvaluationContext evalContext, Method method, Object[] args, Object dbResult) {
        Iterable<?> itemsToCache = extractGranularItems(cacheable, evalContext, dbResult);
        if (itemsToCache == null) {
            return;
        }

        for (Object item : itemsToCache) {
            if (item == null) continue;

            EvaluationContext itemEvalContext = createEvaluationContext(method, args);
            itemEvalContext.setVariable("item", item);
            itemEvalContext.setVariable("result", item);

            String granularKey = evaluateKey(cacheable.granularKey(), itemEvalContext, new Object[]{item});

            for (String gCacheName : cacheable.granularCacheNames()) {
                String fullGranularKey = buildFullKey(cacheable.system(), gCacheName, granularKey);

                if (cacheable.useLocalCache()) {
                    Cache<String, Object> localCache = localCacheManager.getOrCreateCache(
                            gCacheName, cacheable.localMaximumSize(), cacheable.localExpireAfterWriteSeconds()
                    );
                    localCache.put(fullGranularKey, item);
                }

                try {
                    redisService.set(fullGranularKey, serialize(item), cacheable.granularTtlSeconds());
                } catch (Exception e) {
                    appLogger.errorLog(new LogContent("Granular cache write fail: " + fullGranularKey, e.getMessage()), "handleCaching");
                }
            }
        }
    }

    private Iterable<?> extractGranularItems(Cacheable cacheable, EvaluationContext evalContext, Object dbResult) {
        if (!cacheable.granularItems().isEmpty()) {
            Object itemsObj = spelParser.parseExpression(cacheable.granularItems()).getValue(evalContext);
            if (itemsObj instanceof Iterable) {
                return (Iterable<?>) itemsObj;
            }
        } else if (dbResult instanceof Iterable) {
            return (Iterable<?>) dbResult;
        }
        return null;
    }

    private void executeEviction(CacheEvict evict, EvaluationContext evalContext, Object[] args) {
        List<String> keyExpressions = getEvictKeyExpressions(evict);

        for (String cacheName : evict.cacheNames()) {
            try {
                if (evict.allEntries()) {
                    redisService.delByPattern(buildFullKey(evict.system(), cacheName, "*"));
                    if (evict.useLocalCache()) {
                        localCacheManager.invalidateAll(cacheName);
                    }
                } else if (!keyExpressions.isEmpty()) {
                    for (String expr : keyExpressions) {
                        String evaluatedKey = evaluateKey(expr, evalContext, args);
                        String fullKey = buildFullKey(evict.system(), cacheName, evaluatedKey);
                        redisService.del(fullKey);
                        localCacheManager.invalidate(cacheName, fullKey);
                    }
                } else {
                    String fullKey = buildFullKey(evict.system(), cacheName, evaluateKey("", evalContext, args));
                    redisService.del(fullKey);
                    localCacheManager.invalidate(cacheName, fullKey);
                }
            } catch (Exception e) {
                appLogger.errorLog(new LogContent("Evict error for cache: " + cacheName, e.getMessage()), "performEviction");
            }
        }
    }

    private List<String> getEvictKeyExpressions(CacheEvict evict) {
        List<String> expressions = new ArrayList<>();
        if (!evict.key().isEmpty()) expressions.add(evict.key());
        if (evict.keys().length > 0) expressions.addAll(Arrays.asList(evict.keys()));
        return expressions;
    }

    private String buildFullKey(String annotationSystem, String cacheName, String key) {
        return resolveSystem(annotationSystem) + ":" + cacheName + ":" + key;
    }

    private String resolveSystem(String annotationSystem) {
        if (annotationSystem != null && !annotationSystem.trim().isEmpty()) {
            return annotationSystem.trim();
        }

        String propSystem = null;
        if (serviceBundle != null) {
            try {
                propSystem = serviceBundle.getProperty(PROPERTY_SYSTEM_NAME);
            } catch (Exception e) {
                // Ignore bundle lookup failure
            }
        }

        if (propSystem == null || propSystem.trim().isEmpty()) {
            propSystem = System.getProperty(PROPERTY_SYSTEM_NAME);
        }

        String envSystem = System.getenv(ENV_SYSTEM_NAME);

        boolean isPropEmpty = (propSystem == null || propSystem.trim().isEmpty());
        boolean isEnvEmpty = (envSystem == null || envSystem.trim().isEmpty());

        if (isPropEmpty && isEnvEmpty) {
            throw new IllegalStateException("Cache configuration failure: System name is missing.");
        }

        return !isPropEmpty ? propSystem.trim() : envSystem.trim();
    }

    private String evaluateKey(String keyExpression, EvaluationContext evalContext, Object[] args) {
        if (keyExpression != null && !keyExpression.trim().isEmpty()) {
            Object val = spelParser.parseExpression(keyExpression).getValue(evalContext);
            return val != null ? val.toString() : "NULL";
        }
        return (args != null && args.length > 0 && args != null) ? args.toString() : "ALL";
    }

    private EvaluationContext createEvaluationContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        Parameter[] parameters = method.getParameters();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String paramName = parameters[i].isNamePresent() ? parameters[i].getName() : "p" + i;
                context.setVariable(paramName, args[i]);
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
            }
        }
        return context;
    }

    private String serialize(Object object) {
        try {
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (Exception e) {
            return null;
        }
    }

    private Object deserialize(String json, Method method) {
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory().constructType(method.getGenericReturnType()));
        } catch (Exception e) {
            return null;
        }
    }
}