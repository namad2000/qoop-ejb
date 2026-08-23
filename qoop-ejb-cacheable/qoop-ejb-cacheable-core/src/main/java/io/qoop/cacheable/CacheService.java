package io.qoop.cacheable;

import io.qoop.global.model.LogContent;
import ir.tamin.framework.cdi.util.WebProperties;
import ir.tamin.framework.core.util.Bundle;
import ir.tamin.framework.logging.api.logger.AppLogger;
import ir.tamin.framework.logging.api.qualifier.ApplicationLogger;
import redis.clients.jedis.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import java.util.List;

@Singleton
@Startup
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class CacheService {

    @Inject
    @ApplicationLogger
    private AppLogger appLogger;

    @Inject
    @WebProperties
    private Bundle serviceBundle;

    private JedisPool jedisPool;

    @PostConstruct
    public void init() {
        try {
            JedisPoolConfig poolConfig = buildPoolConfig();

            String host = serviceBundle.getProperty("redis.host");
            int port = Integer.parseInt(serviceBundle.getProperty("redis.port"));
            String username = serviceBundle.getProperty("redis.username");
            String password = serviceBundle.getProperty("redis.password");

            if (isNotBlank(username)) {
                jedisPool = new JedisPool(poolConfig, host, port, Protocol.DEFAULT_TIMEOUT, username, password);
            } else if (isNotBlank(password)) {
                jedisPool = new JedisPool(poolConfig, host, port, Protocol.DEFAULT_TIMEOUT, password);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port);
            }
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error initializing Redis JedisPool", e.getMessage()), "init");
        }
    }

    private JedisPoolConfig buildPoolConfig() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(4);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        return poolConfig;
    }

    // ==========================================
    // Redis Operations
    // ==========================================

    public void set(String key, String value, long ttlSeconds) {
        if (!isPoolAvailable()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, value);
            } else {
                jedis.set(key, value);
            }
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error setting key in Redis: " + key, e.getMessage()), "set");
        }
    }

    public String get(String key) {
        if (!isPoolAvailable()) return null;

        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error getting key from Redis: " + key, e.getMessage()), "get");
            return null;
        }
    }

    public void del(String key) {
        if (!isPoolAvailable()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error deleting key from Redis: " + key, e.getMessage()), "del");
        }
    }

    public void delByPattern(String pattern) {
        if (!isPoolAvailable()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams scanParams = new ScanParams().match(pattern).count(100);
            String cursor = ScanParams.SCAN_POINTER_START;

            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                List<String> keys = scanResult.getResult();

                if (keys != null && !keys.isEmpty()) {
                    jedis.del(keys.toArray(new String[0]));
                }
                cursor = scanResult.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error deleting pattern from Redis: " + pattern, e.getMessage()), "delByPattern");
        }
    }

    public Jedis getResource() {
        if (!isPoolAvailable()) return null;

        try {
            return jedisPool.getResource();
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error getting resource from JedisPool", e.getMessage()), "getResource");
            return null;
        }
    }

    public void publish(String channel, String message) {
        if (!isPoolAvailable()) return;

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, message);
        } catch (Exception e) {
            appLogger.errorLog(new LogContent("Error publishing to Redis channel: " + channel, e.getMessage()), "publish");
        }
    }

    // ==========================================
    // Helper & Utility Methods
    // ==========================================

    private boolean isPoolAvailable() {
        return jedisPool != null && !jedisPool.isClosed();
    }

    private String getPropertyOrDefault(String propertyName, String defaultValue) {
        try {
            String val = serviceBundle.getProperty(propertyName);
            return isNotBlank(val) ? val : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int getIntPropertyOrDefault(String propertyName, int defaultValue) {
        try {
            String val = serviceBundle.getProperty(propertyName);
            return isNotBlank(val) ? Integer.parseInt(val.trim()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean isNotBlank(String str) {
        return str != null && !str.trim().isEmpty();
    }

    @PreDestroy
    public void destroy() {
        if (isPoolAvailable()) {
            jedisPool.close();
        }
    }
}