package io.qoop.util;

import io.qoop.global.model.LogContent;
import ir.tamin.framework.cdi.util.WebProperties;
import ir.tamin.framework.core.util.Bundle;
import ir.tamin.framework.logging.api.logger.AppLogger;
import ir.tamin.framework.logging.api.qualifier.ApplicationLogger;

import javax.ejb.*;
import javax.inject.Inject;
import java.util.Optional;

@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
@Lock(LockType.READ)
public class ServiceBundleProvider {

    @Inject
    @WebProperties
    private Bundle serviceBundle;

    @Inject
    @ApplicationLogger
    private AppLogger appLogger;


    public String getPropertySafe(String key) {
        if (serviceBundle == null || key == null) {
            return "";
        }
        try {
            String value = serviceBundle.getProperty(key);
            return value != null ? value.trim() : "";
        } catch (Exception e) {
            appLogger.errorLog(
                    new LogContent("Failed to read property for key: " + key, e.getMessage()),
                    "getPropertySafe"
            );
            return "";
        }
    }


    public Optional<String> getOptionalProperty(String key) {
        String val = getPropertySafe(key);
        return val.isEmpty() ? Optional.empty() : Optional.of(val);
    }


    public int getIntProperty(String key, int defaultValue) {
        String value = getPropertySafe(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            appLogger.errorLog(
                    new LogContent("Invalid integer format for key: " + key + ", value: " + value, e.getMessage()),
                    "getIntProperty"
            );
            return defaultValue;
        }
    }
}