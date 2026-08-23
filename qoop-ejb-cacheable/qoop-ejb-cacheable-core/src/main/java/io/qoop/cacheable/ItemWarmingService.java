package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.gson.Gson;
import io.qoop.util.EvaluationService;
import io.qoop.util.EvaluationContextData;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ItemWarmingService {

    private final Gson gson = new Gson();

    @Inject
    private EvaluationService evaluationService;

    @Inject
    private CacheService redisService;

    @Inject
    private LocalCacheManager localCacheManager;

    public void processItemWarming(Cacheable cacheable, EvaluationContextData evalData, Object result, String system) {
        Iterable<?> items;
        if (!evaluationService.isEmpty(cacheable.itemSource())) {
            Object sourceObj = evaluationService.getParser().parseExpression(cacheable.itemSource()).getValue(evalData.context);
            items = convertToIterable(sourceObj);
        } else {
            items = convertToIterable(result);
        }

        if (items == null) return;

        String itemCacheName = evaluationService.getFirstCacheName(cacheable.itemCacheNames());
        if (itemCacheName.isEmpty()) return;

        for (Object item : items) {
            if (item == null) continue;
            try {
                StandardEvaluationContext itemContext = new StandardEvaluationContext(evalData.rootObject);
                itemContext.setVariables(evalData.variables);
                itemContext.setVariable("item", item);

                String itemKey = evaluationService.getParser().parseExpression(cacheable.itemKey()).getValue(itemContext, String.class);
                String fullItemKey = system + ":" + itemCacheName + ":" + itemKey;
                String itemJson = gson.toJson(item);

                redisService.set(fullItemKey, itemJson, cacheable.ttlSeconds());

                if (cacheable.useLocalCache()) {
                    Cache<String, Object> l1Cache = localCacheManager.getOrCreateCache(itemCacheName, cacheable.localMaximumSize(), cacheable.localExpireAfterWriteSeconds());
                    if (l1Cache != null) {
                        l1Cache.put(fullItemKey, item);
                    }
                }
            } catch (Exception e) {
                // Exception suppressed
            }
        }
    }

    private Iterable<?> convertToIterable(Object obj) {
        if (obj instanceof Iterable) {
            return (Iterable<?>) obj;
        } else if (obj != null && obj.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            int length = Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                list.add(Array.get(obj, i));
            }
            return list;
        }
        return null;
    }
}