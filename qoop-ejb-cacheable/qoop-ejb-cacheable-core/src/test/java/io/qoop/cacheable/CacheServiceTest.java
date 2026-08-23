package io.qoop.cacheable;

import io.qoop.global.model.LogContent;
import io.qoop.util.ServiceBundleProvider;
import ir.tamin.framework.logging.api.logger.AppLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheService Unit Tests")
class CacheServiceTest {

    @Mock
    private AppLogger appLogger;

    @Mock
    private ServiceBundleProvider bundleProvider;

    @Mock
    private JedisPool jedisPool;

    @Mock
    private Jedis jedis;

    @InjectMocks
    private CacheService cacheService;

    @BeforeEach
    void setUp() throws Exception {
        injectJedisPool(jedisPool);
    }

    private void injectJedisPool(JedisPool pool) throws Exception {
        Field field = CacheService.class.getDeclaredField("jedisPool");
        field.setAccessible(true);
        field.set(cacheService, pool);
    }

    // =========================================================================
    // 1. Initializer Tests (Username, Password, No Auth & Exceptions)
    // =========================================================================

    @Test
    @DisplayName("init with username and password should initialize jedisPool successfully")
    void testInit_WithUsernameAndPassword() throws Exception {
        injectJedisPool(null);
        lenient().when(bundleProvider.getPropertySafe("redis.host")).thenReturn("127.0.0.1");
        lenient().when(bundleProvider.getIntProperty("redis.port", 6379)).thenReturn(6379);
        lenient().when(bundleProvider.getPropertySafe("redis.username")).thenReturn("admin");
        lenient().when(bundleProvider.getPropertySafe("redis.password")).thenReturn("secret");

        cacheService.init();
        verifyNoInteractions(appLogger);
    }

    @Test
    @DisplayName("init with password only should initialize jedisPool successfully")
    void testInit_WithPasswordOnly() throws Exception {
        injectJedisPool(null);
        lenient().when(bundleProvider.getPropertySafe("redis.host")).thenReturn("127.0.0.1");
        lenient().when(bundleProvider.getIntProperty("redis.port", 6379)).thenReturn(6379);
        lenient().when(bundleProvider.getPropertySafe("redis.username")).thenReturn("");
        lenient().when(bundleProvider.getPropertySafe("redis.password")).thenReturn("secret");

        cacheService.init();
        verifyNoInteractions(appLogger);
    }

    @Test
    @DisplayName("init without authentication credentials")
    void testInit_WithoutAuth() throws Exception {
        injectJedisPool(null);
        lenient().when(bundleProvider.getPropertySafe("redis.host")).thenReturn("127.0.0.1");
        lenient().when(bundleProvider.getIntProperty("redis.port", 6379)).thenReturn(6379);
        lenient().when(bundleProvider.getPropertySafe("redis.username")).thenReturn("");
        lenient().when(bundleProvider.getPropertySafe("redis.password")).thenReturn("");

        cacheService.init();
        verifyNoInteractions(appLogger);
    }

    @Test
    @DisplayName("init should log error when exception is thrown")
    void testInit_Exception_LogsError() throws Exception {
        injectJedisPool(null);
        when(bundleProvider.getPropertySafe("redis.host")).thenThrow(new RuntimeException("Configuration error"));

        cacheService.init();
        verify(appLogger, times(1)).errorLog(any(LogContent.class), eq("init"));
    }

    // =========================================================================
    // 2. Set Operations (TTL > 0, TTL <= 0, Pool Null & Exception)
    // =========================================================================

    @Test
    void testSet_WithPositiveTTL_CallsSetex() {
        when(jedisPool.getResource()).thenReturn(jedis);
        cacheService.set("key1", "val1", 100);
        verify(jedis, times(1)).setex("key1", 100L, "val1");
    }

    @Test
    void testSet_WithZeroOrNegativeTTL_CallsSet() {
        when(jedisPool.getResource()).thenReturn(jedis);
        cacheService.set("key1", "val1", 0);
        verify(jedis, times(1)).set("key1", "val1");
    }

    @Test
    void testSet_NullPool_BypassesOperation() throws Exception {
        injectJedisPool(null);
        cacheService.set("key1", "val1", 100);
        verifyNoInteractions(appLogger);
    }

    @Test
    void testSet_Exception_LogsError() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection error"));
        cacheService.set("key1", "val1", 100);
        verify(appLogger, times(1)).errorLog(any(LogContent.class), eq("set"));
    }

    // =========================================================================
    // 3. Get Operations (Success, Pool Null & Exception)
    // =========================================================================

    @Test
    void testGet_Success() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("key1")).thenReturn("val1");
        assertEquals("val1", cacheService.get("key1"));
    }

    @Test
    void testGet_NullPool_ReturnsNull() throws Exception {
        injectJedisPool(null);
        assertNull(cacheService.get("key1"));
    }

    @Test
    void testGet_Exception_LogsError() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection error"));
        assertNull(cacheService.get("key1"));
        verify(appLogger, times(1)).errorLog(any(LogContent.class), eq("get"));
    }

    // =========================================================================
    // 4. Delete Operations (Success, Pool Null & Exception)
    // =========================================================================

    @Test
    void testDel_Success() {
        when(jedisPool.getResource()).thenReturn(jedis);
        cacheService.del("key1");
        verify(jedis, times(1)).del("key1");
    }

    @Test
    void testDel_NullPool_BypassesOperation() throws Exception {
        injectJedisPool(null);
        cacheService.del("key1");
        verifyNoInteractions(appLogger);
    }

    @Test
    void testDel_Exception_LogsError() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection error"));
        cacheService.del("key1");
        verify(appLogger, times(1)).errorLog(any(LogContent.class), eq("del"));
    }

    // =========================================================================
    // 5. Pattern Delete Operations (Single Page, Multi Page Loop, Empty List & Null)
    // =========================================================================

    @Test
    void testDelByPattern_SinglePage() {
        when(jedisPool.getResource()).thenReturn(jedis);
        ScanResult<String> scanResult = new ScanResult<String>(ScanParams.SCAN_POINTER_START, Collections.singletonList("GL:user_cache:123"));
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class))).thenReturn(scanResult);

        cacheService.delByPattern("GL:user_cache:*");
        verify(jedis, times(1)).del(eq(new String[]{"GL:user_cache:123"}));
    }

    @Test
    void testDelByPattern_MultiplePages_ShouldLoopUntilCursorZero() {
        when(jedisPool.getResource()).thenReturn(jedis);

        ScanResult<String> page1 = new ScanResult<String>("100", Arrays.asList("key1", "key2"));
        ScanResult<String> page2 = new ScanResult<String>(ScanParams.SCAN_POINTER_START, Collections.singletonList("key3"));

        when(jedis.scan(anyString(), any(ScanParams.class)))
                .thenReturn(page1)
                .thenReturn(page2);

        cacheService.delByPattern("pattern:*");

        verify(jedis, times(1)).del(eq(new String[]{"key1", "key2"}));
        verify(jedis, times(1)).del(eq(new String[]{"key3"}));
    }

    @Test
    void testDelByPattern_EmptyResult_ShouldSkipDelCall() {
        when(jedisPool.getResource()).thenReturn(jedis);
        ScanResult<String> emptyResult = new ScanResult<String>(ScanParams.SCAN_POINTER_START, Collections.<String>emptyList());
        when(jedis.scan(anyString(), any(ScanParams.class))).thenReturn(emptyResult);

        cacheService.delByPattern("empty:*");
        verify(jedis, never()).del(any(String[].class));
    }

    @Test
    void testDelByPattern_NullKeysInResult_ShouldSkipDelCall() {
        when(jedisPool.getResource()).thenReturn(jedis);
        ScanResult<String> nullResult = new ScanResult<String>(ScanParams.SCAN_POINTER_START, null);
        when(jedis.scan(anyString(), any(ScanParams.class))).thenReturn(nullResult);

        cacheService.delByPattern("null:*");
        verify(jedis, never()).del(any(String[].class));
    }

    @Test
    void testDelByPattern_NullPool_BypassesOperation() throws Exception {
        injectJedisPool(null);
        cacheService.delByPattern("pattern:*");
        verifyNoInteractions(appLogger);
    }

    @Test
    void testDelByPattern_Exception_LogsError() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis error"));
        cacheService.delByPattern("GL:user_cache:*");
        verify(appLogger, times(1)).errorLog(any(LogContent.class), eq("delByPattern"));
    }

    // =========================================================================
    // 6. New Methods Tests (GetResource & Publish)
    // =========================================================================

    @Test
    void testGetResource_Success() {
        when(jedisPool.getResource()).thenReturn(jedis);
        assertNotNull(cacheService.getResource());
    }

    @Test
    void testGetResource_NullPool_ReturnsNull() throws Exception {
        injectJedisPool(null);
        assertNull(cacheService.getResource());
    }

    @Test
    void testGetResource_Exception_ReturnsNull() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Pool error"));
        assertNull(cacheService.getResource());
    }

    @Test
    void testPublish_Success() {
        when(jedisPool.getResource()).thenReturn(jedis);
        cacheService.publish("qoop:channel", "test-message");
        verify(jedis, times(1)).publish("qoop:channel", "test-message");
    }

    @Test
    void testPublish_NullPool_BypassesOperation() throws Exception {
        injectJedisPool(null);
        cacheService.publish("qoop:channel", "test-message");
        verifyNoInteractions(appLogger);
    }

    @Test
    void testPublish_Exception_LogsError() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Publish error"));
        cacheService.publish("qoop:channel", "test-message");
        verify(appLogger, times(1)).errorLog(any(LogContent.class), eq("publish"));
    }

    // =========================================================================
    // 7. PreDestroy / Lifecycle Tests
    // =========================================================================

    @Test
    void testDestroy_PoolOpen_ClosesPool() {
        when(jedisPool.isClosed()).thenReturn(false);
        cacheService.destroy();
        verify(jedisPool, times(1)).close();
    }

    @Test
    void testDestroy_PoolAlreadyClosed_DoesNotCloseAgain() {
        when(jedisPool.isClosed()).thenReturn(true);
        cacheService.destroy();
        verify(jedisPool, never()).close();
    }

    @Test
    void testDestroy_PoolNull_DoesNotThrowException() throws Exception {
        injectJedisPool(null);
        assertDoesNotThrow(() -> cacheService.destroy());
    }
}