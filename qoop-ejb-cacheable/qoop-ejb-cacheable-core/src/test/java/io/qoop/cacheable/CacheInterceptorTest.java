package io.qoop.cacheable;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.gson.Gson;
import io.qoop.global.model.LogContent;
import io.qoop.util.EvaluationContextData;
import io.qoop.util.EvaluationService;
import ir.tamin.framework.core.util.Bundle;
import ir.tamin.framework.logging.api.logger.AppLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheInterceptorTest {

    @Mock
    private CacheService redisService;
    @Mock
    private LocalCacheManager localCacheManager;
    @Mock
    private AppLogger appLogger;
    @Mock
    private Bundle serviceBundle;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private ItemWarmingService itemWarmingService;
    @Mock
    private CachePubSubService pubSubService;
    @Mock
    private InvocationContext invocationContext;
    @Mock
    private Cache<String, Object> caffeineCache;

    @InjectMocks
    private CacheInterceptor interceptor;

    private EvaluationContextData evalData;

    @BeforeEach
    void setUp() {
        evalData = new EvaluationContextData();
        evalData.context = new StandardEvaluationContext();

        when(evaluationService.createEvaluationContext(invocationContext)).thenReturn(evalData);
        when(evaluationService.isEmpty(anyString())).thenAnswer(inv -> {
            String val = inv.getArgument(0);
            return val == null || val.trim().isEmpty();
        });
        when(serviceBundle.getProperty("cache.system.name")).thenReturn("DEFAULT_SYS");
        when(localCacheManager.getOrCreateCache(anyString(), anyLong(), anyLong())).thenReturn(caffeineCache);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("cache.system.name");
    }

    // =========================================================================
    // 1. Cacheable Tests
    // =========================================================================

    @Test
    void testGetUser_CacheMiss_ShouldInvokeAndStore() throws Exception {
        Method method = DummyTarget.class.getMethod("getUser", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(invocationContext.getParameters()).thenReturn(new Object[]{"user-100"});
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("user-100");
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateUnless(anyString(), any(), any())).thenReturn(false);
        when(caffeineCache.getIfPresent(anyString())).thenReturn(null);
        when(redisService.get(anyString())).thenReturn(null);

        DummyDto expectedDto = new DummyDto("user-100", "Dawood Akbari");
        when(invocationContext.proceed()).thenReturn(expectedDto);

        Object result = interceptor.handleCaching(invocationContext);

        assertNotNull(result);
        assertEquals("user-100", ((DummyDto) result).getId());
        verify(redisService).set(eq("DEFAULT_SYS:user_cache:user-100"), anyString(), eq(60L));
        verify(caffeineCache).put("DEFAULT_SYS:user_cache:user-100", expectedDto);
    }

    @Test
    void testGetCustomSystemUser_ShouldUseCustomSystem() throws Exception {
        Method method = DummyTarget.class.getMethod("getCustomSystemUser", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(invocationContext.getParameters()).thenReturn(new Object[]{"hr-01"});
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("hr-01");
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(caffeineCache.getIfPresent("HR:user_cache:hr-01")).thenReturn(new DummyDto("hr-01", "HR User"));

        Object result = interceptor.handleCaching(invocationContext);

        assertNotNull(result);
        verify(caffeineCache).getIfPresent("HR:user_cache:hr-01");
        verify(invocationContext, never()).proceed();
    }

    @Test
    void testGetMultiCacheUser_ShouldCheckMultipleCaches() throws Exception {
        Method method = DummyTarget.class.getMethod("getMultiCacheUser", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(invocationContext.getParameters()).thenReturn(new Object[]{"multi-1"});
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("multi-1");
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(caffeineCache.getIfPresent(anyString())).thenReturn(null);

        DummyDto dto = new DummyDto("multi-1", "Multi User");
        when(redisService.get("DEFAULT_SYS:cache_one:multi-1")).thenReturn(new Gson().toJson(dto));

        Object result = interceptor.handleCaching(invocationContext);

        assertNotNull(result);
        verify(redisService).get("DEFAULT_SYS:cache_one:multi-1");
        verify(caffeineCache).put("DEFAULT_SYS:cache_one:multi-1", dto);
    }

    @Test
    void testGetUserConditional_ConditionFalse_ShouldBypassCache() throws Exception {
        Method method = DummyTarget.class.getMethod("getUserConditional", String.class, boolean.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(false);
        when(invocationContext.proceed()).thenReturn(new DummyDto("c-1", "Conditional"));

        Object result = interceptor.handleCaching(invocationContext);

        assertNotNull(result);
        verify(invocationContext).proceed();
        verifyNoInteractions(redisService);
    }

    @Test
    void testGetUserUnlessNull_UnlessTrue_ShouldNotCache() throws Exception {
        Method method = DummyTarget.class.getMethod("getUserUnlessNull", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("null-key");
        when(invocationContext.proceed()).thenReturn(null);
        when(evaluationService.evaluateUnless(anyString(), any(), any())).thenReturn(true);

        Object result = interceptor.handleCaching(invocationContext);

        assertNull(result);
        verify(redisService, never()).set(anyString(), anyString(), anyLong());
    }

    @Test
    void testGetAllUsersWithItemWarming_ShouldTriggerWarming() throws Exception {
        Method method = DummyTarget.class.getMethod("getAllUsersWithItemWarming");
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("all");
        when(caffeineCache.getIfPresent(anyString())).thenReturn(null);
        when(redisService.get(anyString())).thenReturn(null);

        List<DummyDto> list = Collections.singletonList(new DummyDto("1", "User A"));
        when(invocationContext.proceed()).thenReturn(list);
        when(evaluationService.evaluateUnless(anyString(), any(), any())).thenReturn(false);

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals(list, result);
        verify(itemWarmingService).processItemWarming(any(), eq(evalData), eq(list), eq("DEFAULT_SYS"));
    }

    @Test
    void testGetUsersWithCustomItemSource_ShouldWork() throws Exception {
        Method method = DummyTarget.class.getMethod("getUsersWithCustomItemSource", List.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("all");
        when(caffeineCache.getIfPresent(anyString())).thenReturn(null);
        when(redisService.get(anyString())).thenReturn(null);

        List<DummyDto> list = Arrays.asList(new DummyDto("1", "A"), new DummyDto("2", "B"));
        when(invocationContext.proceed()).thenReturn(list);
        when(evaluationService.evaluateUnless(anyString(), any(), any())).thenReturn(false);

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals(list, result);
        verify(itemWarmingService).processItemWarming(any(), eq(evalData), eq(list), eq("DEFAULT_SYS"));
    }

    @Test
    void testNoAnnotationMethod_ShouldProceedDirectly() throws Exception {
        Method method = DummyTarget.class.getMethod("noAnnotationMethod");
        when(invocationContext.getMethod()).thenReturn(method);
        when(invocationContext.proceed()).thenReturn("direct");

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals("direct", result);
        verify(invocationContext).proceed();
        verifyNoInteractions(redisService);
    }

    // =========================================================================
    // 2. CacheEvict Tests
    // =========================================================================

    @Test
    void testDeleteConditional_ShouldEvictIfConditionMet() throws Exception {
        Method method = DummyTarget.class.getMethod("deleteConditional", String.class, boolean.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("del-1");
        when(invocationContext.proceed()).thenReturn("deleted-res");

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals("deleted-res", result);
        verify(redisService).del("DEFAULT_SYS:user_cache:del-1");
        verify(pubSubService).publishEviction("user_cache:DEFAULT_SYS:user_cache:del-1");
    }

    @Test
    void testDeleteBefore_ShouldEvictBeforeInvocation() throws Exception {
        Method method = DummyTarget.class.getMethod("deleteBefore", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("b-key");
        when(invocationContext.proceed()).thenReturn("done");

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals("done", result);
        verify(redisService).del("DEFAULT_SYS:user_cache:b-key");
    }

    @Test
    void testDeleteMultiple_ShouldEvictMultipleKeys() throws Exception {
        Method method = DummyTarget.class.getMethod("deleteMultiple", String.class, String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(eq("#p0"), any())).thenReturn("k1");
        when(evaluationService.evaluateKey(eq("#p1"), any())).thenReturn("k2");
        when(invocationContext.proceed()).thenReturn("multi-done");

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals("multi-done", result);
        verify(redisService).del("DEFAULT_SYS:user_cache:k1");
        verify(redisService).del("DEFAULT_SYS:user_cache:k2");
    }

    @Test
    void testClearUserCache_AllEntries_ShouldEvictPattern() throws Exception {
        Method method = DummyTarget.class.getMethod("clearUserCache");
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(invocationContext.proceed()).thenReturn("cleared");

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals("cleared", result);
        verify(redisService).delByPattern("DEFAULT_SYS:user_cache:*");
        verify(localCacheManager).invalidateAll("user_cache");
        verify(pubSubService).publishEviction("user_cache:*");
    }

    @Test
    void testDeleteDefaultKey_ShouldEvictUsingDefaultKey() throws Exception {
        Method method = DummyTarget.class.getMethod("deleteDefaultKey", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(eq(""), any())).thenReturn("default-key");
        when(invocationContext.proceed()).thenReturn("default-done");

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals("default-done", result);
        verify(redisService).del("DEFAULT_SYS:user_cache:default-key");
    }

    // =========================================================================
    // 3. System Resolution & Exception Handling Tests
    // =========================================================================

    @Test
    void testResolveSystem_FromSystemProperty() throws Exception {
        when(serviceBundle.getProperty("cache.system.name")).thenReturn(null);
        System.setProperty("cache.system.name", "PROP_SYS");

        Method method = DummyTarget.class.getMethod("getUser", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("prop-key");
        when(caffeineCache.getIfPresent(anyString())).thenReturn(null);
        when(redisService.get(anyString())).thenReturn(null);
        when(invocationContext.proceed()).thenReturn(new DummyDto("1", "PropUser"));

        Object result = interceptor.handleCaching(invocationContext);
        assertNotNull(result);
    }

    @Test
    void testResolveSystem_MissingSystem_ShouldThrowIllegalStateException() throws Exception {
        when(serviceBundle.getProperty("cache.system.name")).thenReturn(null);

        System.clearProperty("cache.system.name");
        when(evaluationService.isEmpty(any())).thenReturn(true);

        Method method = DummyTarget.class.getMethod("getUser", String.class);
        when(invocationContext.getMethod()).thenReturn(method);

        assertThrows(IllegalStateException.class, () -> interceptor.handleCaching(invocationContext));
    }

    @Test
    void testCacheReadException_ShouldLogAndProceed() throws Exception {
        Method method = DummyTarget.class.getMethod("getUser", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("err-key");
        when(caffeineCache.getIfPresent(anyString())).thenReturn(null);
        when(redisService.get(anyString())).thenThrow(new RuntimeException("Redis error"));
        when(invocationContext.proceed()).thenReturn("fallback");
        when(evaluationService.evaluateUnless(anyString(), any(), any())).thenReturn(false);

        Object result = interceptor.handleCaching(invocationContext);

        assertEquals("fallback", result);
        verify(appLogger).errorLog(any(LogContent.class), eq("handleCaching"));
    }

    @Test
    void testCacheWriteException_ShouldLogAndReturnResult() throws Exception {
        Method method = DummyTarget.class.getMethod("getUser", String.class);
        when(invocationContext.getMethod()).thenReturn(method);
        when(evaluationService.evaluateCondition(anyString(), any())).thenReturn(true);
        when(evaluationService.evaluateKey(anyString(), any())).thenReturn("write-key");
        when(caffeineCache.getIfPresent(anyString())).thenReturn(null);
        when(redisService.get(anyString())).thenReturn(null);

        DummyDto expectedDto = new DummyDto("1", "Test");
        when(invocationContext.proceed()).thenReturn(expectedDto);
        when(evaluationService.evaluateUnless(anyString(), any(), any())).thenReturn(false);
        doThrow(new RuntimeException("Write failed")).when(redisService).set(anyString(), anyString(), anyLong());

        Object result = interceptor.handleCaching(invocationContext);

        assertNotNull(result);
        verify(appLogger).errorLog(any(LogContent.class), eq("handleCaching"));
    }

    // =========================================================================
    // 4. SelfReferencingDto Helper Test
    // =========================================================================

    @Test
    void testSelfReferencingDto_ShouldConstructCorrectly() {
        SelfReferencingDto dto = new SelfReferencingDto();
        assertNotNull(dto.getSelf());
        assertEquals(dto, dto.getSelf());
    }

    // =========================================================================
    // Dummy Target & DTO Helper Classes
    // =========================================================================

    static class DummyTarget {
        @Cacheable(cacheNames = "user_cache", key = "#p0", ttlSeconds = 60)
        public DummyDto getUser(String id) {
            return null;
        }

        @Cacheable(system = "HR", cacheNames = "user_cache", key = "#p0", ttlSeconds = 1800)
        public DummyDto getCustomSystemUser(String id) {
            return null;
        }

        @Cacheable(cacheNames = {"cache_one", "cache_two"}, key = "#p0", ttlSeconds = 60)
        public DummyDto getMultiCacheUser(String id) {
            return null;
        }

        @Cacheable(cacheNames = "user_cache", key = "#p0", condition = "#p1")
        public DummyDto getUserConditional(String id, boolean enabled) {
            return null;
        }

        @Cacheable(cacheNames = "user_cache", key = "#p0", unless = "#result == null")
        public DummyDto getUserUnlessNull(String id) {
            return null;
        }

        @Cacheable(
                cacheNames = "users_all",
                key = "'all'",
                enableItemWarming = true,
                itemCacheNames = "users_single",
                itemKey = "#item.id"
        )
        public List<DummyDto> getAllUsersWithItemWarming() {
            return null;
        }

        @Cacheable(
                cacheNames = "users_all_custom",
                key = "'all'",
                enableItemWarming = true,
                itemCacheNames = "users_single",
                itemKey = "#item.id",
                itemSource = "#p0"
        )
        public List<DummyDto> getUsersWithCustomItemSource(List<DummyDto> list) {
            return null;
        }

        @CacheEvict(cacheNames = "user_cache", key = "#p0", condition = "#p1")
        public String deleteConditional(String id, boolean enabled) {
            return null;
        }

        @CacheEvict(cacheNames = "user_cache", key = "#p0", beforeInvocation = true)
        public String deleteBefore(String id) {
            return null;
        }

        @CacheEvict(cacheNames = "user_cache", keys = {"#p0", "#p1"})
        public String deleteMultiple(String id1, String id2) {
            return null;
        }

        @CacheEvict(cacheNames = "user_cache", allEntries = true)
        public String clearUserCache() {
            return null;
        }

        @CacheEvict(cacheNames = "user_cache")
        public String deleteDefaultKey(String id) {
            return null;
        }

        public String noAnnotationMethod() {
            return null;
        }
    }

    static class DummyDto {
        private String id;
        private String name;

        public DummyDto() {
        }

        public DummyDto(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DummyDto dummyDto = (DummyDto) o;
            return java.util.Objects.equals(id, dummyDto.id) &&
                    java.util.Objects.equals(name, dummyDto.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, name);
        }
    }

    static class SelfReferencingDto {
        private final SelfReferencingDto self = this;

        public SelfReferencingDto getSelf() {
            return self;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o != null && getClass() == o.getClass();
        }

        @Override
        public int hashCode() {
            return 31;
        }
    }
}