package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import io.qoop.util.EvaluationContextData;
import io.qoop.util.EvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ItemWarmingService Unit Tests")
class ItemWarmingServiceTest {

    @InjectMocks
    private ItemWarmingService itemWarmingService;

    @Spy
    private EvaluationService evaluationService = new EvaluationService();

    @Mock
    private CacheService redisService;

    @Mock
    private LocalCacheManager localCacheManager;

    @Mock
    private Cache<String, Object> l1Cache;

    private EvaluationContextData evalData;

    @BeforeEach
    void setUp() {
        evalData = new EvaluationContextData();
        evalData.context = new StandardEvaluationContext();
        evalData.variables = new HashMap<>();
        evalData.rootObject = new Object();
    }

    private Cacheable createCacheable(
            final String itemSource,
            final String[] itemCacheNames,
            final String itemKey,
            final boolean useLocalCache,
            final long ttlSeconds,
            final long localMaximumSize,
            final long localExpireAfterWriteSeconds) {
        return new Cacheable() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return Cacheable.class;
            }

            @Override
            public String system() {
                return "testSys";
            }

            @Override
            public String[] cacheNames() {
                return new String[0];
            }

            @Override
            public String key() {
                return "";
            }

            @Override
            public String condition() {
                return "";
            }

            @Override
            public String unless() {
                return "";
            }

            @Override
            public long ttlSeconds() {
                return ttlSeconds;
            }

            @Override
            public boolean startup() {
                return false;
            }

            @Override
            public boolean enableItemWarming() {
                return true;
            }

            @Override
            public String[] itemCacheNames() {
                return itemCacheNames;
            }

            @Override
            public String itemKey() {
                return itemKey;
            }

            @Override
            public String itemSource() {
                return itemSource;
            }

            @Override
            public boolean useLocalCache() {
                return useLocalCache;
            }

            @Override
            public long localMaximumSize() {
                return localMaximumSize;
            }

            @Override
            public long localExpireAfterWriteSeconds() {
                return localExpireAfterWriteSeconds;
            }
        };
    }

    @Nested
    @DisplayName("Process Item Warming Early Return & Guard Tests")
    class EarlyReturnTests {

        @Test
        @DisplayName("Should return early when source and result yield non-iterable object")
        void processItemWarming_WhenSourceAndResultNotIterable_ShouldReturnEarly() {
            Cacheable cacheable = createCacheable("", new String[]{"itemCache"}, "#item", true, 60, 100, 100);

            itemWarmingService.processItemWarming(cacheable, evalData, "NotAnIterable", "system");

            verifyNoInteractions(redisService);
            verifyNoInteractions(localCacheManager);
        }

        @Test
        @DisplayName("Should return early when items are null")
        void processItemWarming_WhenItemsAreNull_ShouldReturnEarly() {
            Cacheable cacheable = createCacheable("", new String[]{"itemCache"}, "#item", true, 60, 100, 100);

            itemWarmingService.processItemWarming(cacheable, evalData, null, "system");

            verifyNoInteractions(redisService);
            verifyNoInteractions(localCacheManager);
        }

        @Test
        @DisplayName("Should return early when itemCacheName is empty string")
        void processItemWarming_WhenItemCacheNameIsEmpty_ShouldReturnEarly() {
            Cacheable cacheable = createCacheable("", new String[]{""}, "#item", true, 60, 100, 100);
            List<String> items = Collections.singletonList("item1");

            itemWarmingService.processItemWarming(cacheable, evalData, items, "system");

            verifyNoInteractions(redisService);
            verifyNoInteractions(localCacheManager);
        }
    }

    @Nested
    @DisplayName("Iterable & Array Conversion Tests")
    class ConversionAndWarmingTests {

        @Test
        @DisplayName("Should process Iterable items using result when itemSource is empty")
        void processItemWarming_WithIterableResult_ShouldWarmCache() {
            Cacheable cacheable = createCacheable("", new String[]{"userCache"}, "'user_' + #item", false, 300, 1000, 3600);
            List<String> items = Arrays.asList("101", "102");

            itemWarmingService.processItemWarming(cacheable, evalData, items, "sysApp");

            verify(redisService).set("sysApp:userCache:user_101", "\"101\"", 300);
            verify(redisService).set("sysApp:userCache:user_102", "\"102\"", 300);
            verifyNoInteractions(localCacheManager);
        }

        @Test
        @DisplayName("Should process Array items using itemSource SpEL expression")
        void processItemWarming_WithArrayFromItemSource_ShouldWarmCache() {
            String[] arrayItems = new String[]{"A", "B"};
            evalData.context.setVariable("payload", arrayItems);

            Cacheable cacheable = createCacheable("#payload", new String[]{"letterCache"}, "#item", false, 600, 500, 300);

            itemWarmingService.processItemWarming(cacheable, evalData, null, "sysApp");

            verify(redisService).set("sysApp:letterCache:A", "\"A\"", 600);
            verify(redisService).set("sysApp:letterCache:B", "\"B\"", 600);
        }

        @Test
        @DisplayName("Should skip null elements inside items collection")
        void processItemWarming_WithNullItemsInCollection_ShouldSkipNulls() {
            Cacheable cacheable = createCacheable("", new String[]{"myCache"}, "#item", false, 100, 100, 100);
            List<String> items = Arrays.asList("validItem", null);

            itemWarmingService.processItemWarming(cacheable, evalData, items, "sys");

            verify(redisService).set("sys:myCache:validItem", "\"validItem\"", 100);
            verify(redisService, never()).set(eq("sys:myCache:null"), anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("L1 Local Cache (Caffeine) Integration Tests")
    class LocalCacheTests {

        @Test
        @DisplayName("Should populate L1 local cache when useLocalCache is true and cache exists")
        void processItemWarming_WithLocalCacheEnabled_ShouldPutInL1Cache() {
            Cacheable cacheable = createCacheable("", new String[]{"itemCache"}, "#item", true, 120, 500, 600);
            List<String> items = Collections.singletonList("item1");

            when(localCacheManager.getOrCreateCache("itemCache", 500, 600)).thenReturn(l1Cache);

            itemWarmingService.processItemWarming(cacheable, evalData, items, "sys");

            verify(redisService).set("sys:itemCache:item1", "\"item1\"", 120);
            verify(localCacheManager).getOrCreateCache("itemCache", 500, 600);
            verify(l1Cache).put("sys:itemCache:item1", "item1");
        }

        @Test
        @DisplayName("Should handle gracefully when localCacheManager returns null L1 cache")
        void processItemWarming_WhenL1CacheIsNull_ShouldNotThrowException() {
            Cacheable cacheable = createCacheable("", new String[]{"itemCache"}, "#item", true, 120, 500, 600);
            List<String> items = Collections.singletonList("item1");

            when(localCacheManager.getOrCreateCache("itemCache", 500, 600)).thenReturn(null);

            assertDoesNotThrow(() -> itemWarmingService.processItemWarming(cacheable, evalData, items, "sys"));

            verify(redisService).set("sys:itemCache:item1", "\"item1\"", 120);
            verify(localCacheManager).getOrCreateCache("itemCache", 500, 600);
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("Should suppress exceptions thrown during item processing loop")
        void processItemWarming_WhenExceptionOccurs_ShouldSuppressAndContinue() {
            Cacheable cacheable = createCacheable("", new String[]{"itemCache"}, "#invalidProperty.id", false, 60, 100, 100);
            List<String> items = Collections.singletonList("stringItem");

            assertDoesNotThrow(() -> itemWarmingService.processItemWarming(cacheable, evalData, items, "sys"));

            verify(redisService, never()).set(anyString(), anyString(), anyLong());
        }
    }
}