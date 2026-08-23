package io.qoop.cacheable;

import io.qoop.global.model.LogContent;
import ir.tamin.framework.logging.api.logger.AppLogger;
import ir.tamin.framework.logging.api.qualifier.ApplicationLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Singleton;
import javax.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class CachePubSubService {

    public static final String CHANNEL_NAME = "qoop:cache:evict:channel";

    @Inject
    @ApplicationLogger
    private AppLogger appLogger;

    @Inject
    private CacheService cacheService;

    @Inject
    private LocalCacheManager localCacheManager;

    private ExecutorService listenerExecutor;
    private volatile JedisPubSub activePubSub;

    @PostConstruct
    public void init() {
        listenerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Cache-PubSub-Listener-Thread"));
        listenerExecutor.submit(this::subscribeToChannel);
    }

    public void publishEviction(String message) {
        try {
            cacheService.publish(CHANNEL_NAME, message);
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error publishing cache eviction message", e.getMessage()), "publishEviction");
        }
    }

    private void subscribeToChannel() {
        while (!Thread.currentThread().isInterrupted()) {
            try (Jedis jedis = cacheService.getResource()) {
                if (jedis == null) {
                    Thread.sleep(5000);
                    continue;
                }
                activePubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleClusterEvictionMessage(message);
                    }
                };
                jedis.subscribe(activePubSub, CHANNEL_NAME);
            } catch (Exception e) {
                appLogger.errorLog(new LogContent("Redis Pub/Sub connection lost, reconnecting...", e.getMessage()), "subscribeToChannel");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleClusterEvictionMessage(String message) {
        try {
            String[] parts = message.split(":", 2);
            if (parts.length == 2) {
                String cacheName = parts[0];
                String target = parts[1];
                if (target.endsWith("*")) {
                    localCacheManager.invalidateAll(cacheName);
                } else {
                    localCacheManager.invalidate(cacheName, target);
                }
            }
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error handling cluster eviction message", e.getMessage()), "handleClusterEvictionMessage");
        }
    }

    @PreDestroy
    public void destroy() {
        if (activePubSub != null && activePubSub.isSubscribed()) {
            activePubSub.unsubscribe();
        }
        if (listenerExecutor != null) {
            listenerExecutor.shutdownNow();
        }
    }
}