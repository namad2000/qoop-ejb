package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.gson.Gson;
import io.qoop.global.model.LogContent;
import io.qoop.util.EvaluationContextData;
import io.qoop.util.EvaluationService;
import ir.tamin.framework.logging.api.logger.AppLogger;
import ir.tamin.framework.logging.api.qualifier.ApplicationLogger;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class ItemWarmingService {

    private final Gson gson = new Gson();

    @Inject
    @ApplicationLogger
    private AppLogger appLogger;

    @Inject
    private EvaluationService evaluationService;

    @Inject
    private CacheService redisService;

    @Inject
    private LocalCacheManager localCacheManager;

    public void processItemWarming(Cacheable cacheable, EvaluationContextData evalData, Object result, String system) {
        if (!isValidInput(cacheable, evalData)) {
            return;
        }

        Iterable<?> items = resolveItems(cacheable, evalData, result);
        if (items == null) {
            return;
        }

        String itemCacheName = evaluationService.getFirstCacheName(cacheable.itemCacheNames());
        if (isBlank(itemCacheName) || evaluationService.isEmpty(cacheable.itemKey())) {
            return;
        }

        Expression itemKeyExpression = parseExpression(cacheable.itemKey(), "Failed to parse itemKey expression: ");
        if (itemKeyExpression == null) {
            return;
        }

        warmItems(items, cacheable, evalData, itemKeyExpression, itemCacheName, system);
    }

    private void warmItems(Iterable<?> items, Cacheable cacheable, EvaluationContextData evalData,
                           Expression itemKeyExpression, String itemCacheName, String system) {

        StandardEvaluationContext itemContext = createEvaluationContext(evalData);
        Cache<String, Object> l1Cache = resolveLocalCache(cacheable, itemCacheName);

        for (Object item : items) {
            if (item == null) continue;
            warmSingleItem(item, cacheable, itemContext, itemKeyExpression, itemCacheName, system, l1Cache);
        }
    }

    private void warmSingleItem(Object item, Cacheable cacheable, StandardEvaluationContext itemContext,
                                Expression itemKeyExpression, String itemCacheName, String system,
                                Cache<String, Object> l1Cache) {
        try {
            itemContext.setVariable("item", item);

            String itemKey = itemKeyExpression.getValue(itemContext, String.class);
            if (isBlank(itemKey)) {
                return;
            }

            String fullItemKey = system + ":" + itemCacheName + ":" + itemKey;
            String itemJson = gson.toJson(item);

            redisService.set(fullItemKey, itemJson, cacheable.ttlSeconds());

            if (l1Cache != null) {
                l1Cache.put(fullItemKey, item);
            }
        } catch (Exception e) {
            logError("Error processing warming for item in cache: " + itemCacheName, e, "processItemWarming");
        }
    }

    private Iterable<?> resolveItems(Cacheable cacheable, EvaluationContextData evalData, Object result) {
        try {
            if (!evaluationService.isEmpty(cacheable.itemSource())) {
                Expression sourceExpr = evaluationService.getParser().parseExpression(cacheable.itemSource());
                Object sourceObj = sourceExpr.getValue(evalData.context);
                return convertToIterable(sourceObj);
            } else {
                return convertToIterable(result);
            }
        } catch (Exception e) {
            logError("Error evaluating itemSource expression for cache warming", e, "processItemWarming");
            return null;
        }
    }

    private StandardEvaluationContext createEvaluationContext(EvaluationContextData evalData) {
        StandardEvaluationContext itemContext = new StandardEvaluationContext(evalData.rootObject);
        if (evalData.variables != null) {
            itemContext.setVariables(evalData.variables);
        }
        return itemContext;
    }

    private Cache<String, Object> resolveLocalCache(Cacheable cacheable, String itemCacheName) {
        if (cacheable.useLocalCache()) {
            return localCacheManager.getOrCreateCache(
                    itemCacheName,
                    cacheable.localMaximumSize(),
                    cacheable.localExpireAfterWriteSeconds()
            );
        }
        return null;
    }

    private Expression parseExpression(String expressionText, String errorMessagePrefix) {
        try {
            return evaluationService.getParser().parseExpression(expressionText);
        } catch (Exception e) {
            logError(errorMessagePrefix + expressionText, e, "processItemWarming");
            return null;
        }
    }

    private void logError(String message, Throwable cause, String methodName) {
        if (appLogger != null) {
            String details = cause != null ? cause.getMessage() : "";
            appLogger.errorLog(new LogContent(message, details), methodName);
        }
    }

    private Iterable<?> convertToIterable(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Iterable) {
            return (Iterable<?>) obj;
        } else if (obj.getClass().isArray()) {
            if (obj instanceof Object[]) {
                return Arrays.asList((Object[]) obj);
            } else {
                int length = Array.getLength(obj);
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    list.add(Array.get(obj, i));
                }
                return list;
            }
        }
        return null;
    }

    private boolean isValidInput(Cacheable cacheable, EvaluationContextData evalData) {
        return cacheable != null && evalData != null;
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}