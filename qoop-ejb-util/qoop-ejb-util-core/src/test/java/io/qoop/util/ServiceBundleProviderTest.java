package io.qoop.util;

import io.qoop.global.model.LogContent;
import ir.tamin.framework.core.util.Bundle;
import ir.tamin.framework.logging.api.logger.AppLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceBundleProvider Unit Tests")
class ServiceBundleProviderTest {

    @InjectMocks
    private ServiceBundleProvider bundleProvider;

    @Mock
    private Bundle serviceBundle;

    @Mock
    private AppLogger appLogger;

    @Test
    @DisplayName("getPropertySafe should return value when key exists")
    void getPropertySafe_WhenKeyExists_ShouldReturnValue() {
        when(serviceBundle.getProperty("redis.host")).thenReturn("127.0.0.1");

        String result = bundleProvider.getPropertySafe("redis.host");

        assertEquals("127.0.0.1", result);
        verify(appLogger, never()).errorLog(any(LogContent.class), anyString());
    }

    @Test
    @DisplayName("getPropertySafe should return empty string and log error when exception occurs")
    void getPropertySafe_WhenExceptionThrown_ShouldReturnEmptyStringAndLogError() {
        when(serviceBundle.getProperty("missing.key")).thenThrow(new RuntimeException("Key not found"));

        String result = bundleProvider.getPropertySafe("missing.key");

        assertEquals("", result);
        verify(appLogger).errorLog(any(LogContent.class), eq("getPropertySafe"));
    }

    @Test
    @DisplayName("getPropertySafe should handle null key or serviceBundle gracefully")
    void getPropertySafe_WhenKeyOrBundleIsNull_ShouldReturnEmptyString() {
        assertEquals("", bundleProvider.getPropertySafe(null));
    }

    @Test
    @DisplayName("getOptionalProperty should return Optional with value when key exists")
    void getOptionalProperty_WhenKeyExists_ShouldReturnOptional() {
        when(serviceBundle.getProperty("redis.username")).thenReturn("admin");

        Optional<String> result = bundleProvider.getOptionalProperty("redis.username");

        assertTrue(result.isPresent());
        assertEquals("admin", result.get());
    }

    @Test
    @DisplayName("getIntProperty should return parsed integer when valid string")
    void getIntProperty_WhenValidNumber_ShouldReturnParsedInt() {
        when(serviceBundle.getProperty("redis.port")).thenReturn("6379");

        int result = bundleProvider.getIntProperty("redis.port", 6379);

        assertEquals(6379, result);
    }

    @Test
    @DisplayName("getIntProperty should return default value when value is invalid integer or missing")
    void getIntProperty_WhenInvalidOrMissing_ShouldReturnDefaultValue() {
        when(serviceBundle.getProperty("redis.port.invalid")).thenReturn("not_a_number");
        when(serviceBundle.getProperty("redis.port.missing")).thenReturn("");

        assertEquals(6379, bundleProvider.getIntProperty("redis.port.invalid", 6379));
        assertEquals(6379, bundleProvider.getIntProperty("redis.port.missing", 6379));
        verify(appLogger).errorLog(any(LogContent.class), eq("getIntProperty"));
    }
}