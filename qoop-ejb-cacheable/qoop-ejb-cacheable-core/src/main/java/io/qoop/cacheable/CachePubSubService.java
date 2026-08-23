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
import javax.ejb.Startup;
import javax.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Singleton
@Startup
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
    private volatile boolean stopped = false;

    @PostConstruct
    public void init() {
        listenerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Cache-PubSub-Listener-Thread");
            thread.setDaemon(true);
            return thread;
        });
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
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try (Jedis jedis = cacheService.getResource()) {
                if (jedis == null) {
                    safeSleep(5000);
                    continue;
                }

                activePubSub = createPubSubListener();
                jedis.subscribe(activePubSub, CHANNEL_NAME);

            } catch (Exception e) {
                if (!stopped) {
                    appLogger.errorLog(
                            new LogContent("Redis Pub/Sub connection lost, reconnecting...", e.getMessage()),
                            "subscribeToChannel"
                    );
                    safeSleep(3000);
                }
            }
        }
    }

    private JedisPubSub createPubSubListener() {
        return new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handleClusterEvictionMessage(message);
            }
        };
    }

    private void handleClusterEvictionMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

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

    private void safeSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void destroy() {
        this.stopped = true;

        if (activePubSub != null && activePubSub.isSubscribed()) {
            try {
                activePubSub.unsubscribe();
            } catch (Exception e) {
                appLogger.errorLog(new LogContent("Error during Pub/Sub unsubscribe", e.getMessage()), "destroy");
            }
        }

        if (listenerExecutor != null) {
            listenerExecutor.shutdown();
            try {
                if (!listenerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    listenerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                listenerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}