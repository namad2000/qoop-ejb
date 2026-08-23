package io.qoop.cacheable;

import io.qoop.global.model.LogContent;
import ir.tamin.framework.logging.api.logger.AppLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachePubSubServiceTest {

    @Mock
    private AppLogger appLogger;

    @Mock
    private CacheService cacheService;

    @Mock
    private LocalCacheManager localCacheManager;

    @InjectMocks
    private CachePubSubService pubSubService;

    @Test
    void testPublishEviction_Success() {
        pubSubService.publishEviction("user_cache:123");
        verify(cacheService, times(1)).publish(eq(CachePubSubService.CHANNEL_NAME), eq("user_cache:123"));
    }

    @Test
    void testPublishEviction_Exception_LogsError() {
        doThrow(new RuntimeException("Pub fail")).when(cacheService).publish(anyString(), anyString());
        pubSubService.publishEviction("user_cache:123");
        verify(appLogger, times(1)).errorLog(any(LogContent.class), eq("publishEviction"));
    }
}