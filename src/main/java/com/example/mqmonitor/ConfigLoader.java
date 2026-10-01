package com.example.mqmonitor;

import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filename: ConfigLoader.java
 * Purpose: Loads application-level properties from config.properties and overrides them 
 *          with environment variables at runtime if they are defined.
 * Sensitive-Data Handling: Secure values like username, password, and keystore parameters 
 *                           are read from system environment variables and never hardcoded.
 */
public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private final Properties properties = new Properties();

    public ConfigLoader() {
        loadProperties();
    }

    private void loadProperties() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                logger.warn("Unable to find config.properties in the classpath. Relying on default and environment overrides.");
            } else {
                properties.load(input);
                logger.info("Successfully loaded static config.properties from classpath.");
            }
        } catch (Exception ex) {
            logger.error("Failed to load config.properties file: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Retrieves a config value, checking environment variables first as overrides.
     */
    private String getString(String propKey, String envKey) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return properties.getProperty(propKey);
    }

    /**
     * Retrieves a config integer, checking environment overrides first.
     */
    private int getInt(String propKey, String envKey, int defaultValue) {
        String val = getString(propKey, envKey);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            logger.error("Invalid integer configuration for key {}. Falling back to default: {}", propKey, defaultValue);
            return defaultValue;
        }
    }

    public String getAppName() {
        return getString("app.name", "APP_NAME");
    }

    public String getAppEnv() {
        return getString("app.env", "APP_ENV");
    }

    public String getMqHost() {
        return getString("mq.host", "MQ_HOST");
    }

    public int getMqPort() {
        return getInt("mq.port", "MQ_PORT", 1414);
    }

    public String getMqChannel() {
        return getString("mq.channel", "MQ_CHANNEL");
    }

    public String getMqQueueManager() {
        return getString("mq.queueManager", "MQ_QUEUE_MANAGER");
    }

    public String getMqTestQueue() {
        return getString("mq.testQueue", "MQ_TEST_QUEUE");
    }

    public String getMqUsername() {
        return getString("mq.username", "MQ_USERNAME");
    }

    public String getMqPassword() {
        return getString("mq.password", "MQ_PASSWORD");
    }

    public String getMqSslCipherSuite() {
        return getString("mq.sslCipherSuite", "MQ_SSL_CIPHER_SUITE");
    }

    public String getMqKeystorePath() {
        return getString("mq.keystorePath", "MQ_KEYSTORE_PATH");
    }

    public String getMqKeystorePassword() {
        return getString("mq.keystorePassword", "MQ_KEYSTORE_PASSWORD");
    }
}
