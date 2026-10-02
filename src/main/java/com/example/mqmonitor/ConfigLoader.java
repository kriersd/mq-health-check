package com.example.mqmonitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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

    /**
     * Overloaded constructor for testing allowing direct properties injection.
     */
    public ConfigLoader(Properties properties) {
        if (properties != null) {
            this.properties.putAll(properties);
        }
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

        // Dynamically load .env file from the current directory if it exists to simplify local execution
        File envFile = new File(".env");
        if (envFile.exists() && envFile.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eqIdx = line.indexOf('=');
                    if (eqIdx > 0) {
                        String key = line.substring(0, eqIdx).trim();
                        String val = line.substring(eqIdx + 1).trim();
                        // Strip trailing comments (like '# SENSITIVE')
                        int hashIdx = val.indexOf('#');
                        if (hashIdx >= 0) {
                            val = val.substring(0, hashIdx).trim();
                        }
                        properties.setProperty(key, val);
                    }
                }
                logger.info("Successfully loaded configuration overrides from local .env file.");
            } catch (Exception ex) {
                logger.error("Failed to load local .env file: {}", ex.getMessage());
            }
        }
    }

    /**
     * Retrieves a config value, checking environment variables first as overrides.
     */
    public String getString(String propKey, String envKey) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        // Fall back to .env loaded properties
        String envFileValue = properties.getProperty(envKey);
        if (envFileValue != null && !envFileValue.trim().isEmpty()) {
            return envFileValue;
        }
        return properties.getProperty(propKey);
    }

    /**
     * Retrieves a config integer, checking environment overrides first.
     */
    public int getInt(String propKey, String envKey, int defaultValue) {
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

    /**
     * Retrieves a config boolean, checking environment overrides first.
     */
    public boolean getBoolean(String propKey, String envKey, boolean defaultValue) {
        String val = getString(propKey, envKey);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(val.trim());
    }

    public String getAppName() {
        return getString("app.name", "APP_NAME");
    }

    public String getAppEnv() {
        return getString("app.env", "APP_ENV");
    }

    public int getAppPort() {
        return getInt("app.port", "PORT", 8080);
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

    public String getMqDlqName() {
        return getString("mq.dlqName", "MQ_DLQ_NAME");
    }

    public String getMqIniPath() {
        return getString("mq.iniPath", "MQ_INI_PATH");
    }

    public String getMqDataPath() {
        return getString("mq.dataPath", "MQ_DATA_PATH");
    }

    public String getMqKeystorePath() {
        return getString("mq.keystorePath", "MQ_KEYSTORE_PATH");
    }

    public String getMqKeystorePassword() {
        return getString("mq.keystorePassword", "MQ_KEYSTORE_PASSWORD");
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

    // Dynamic getters for checks
    public boolean isCheckEnabled(String checkKey) {
        return getBoolean("check." + checkKey + ".enabled", "CHECK_" + checkKey.toUpperCase() + "_ENABLED", true);
    }

    public String getCheckSeverity(String checkKey) {
        return getString("check." + checkKey + ".severity", "CHECK_" + checkKey.toUpperCase() + "_SEVERITY");
    }

    public int getCheckThreshold(String checkKey, int defaultValue) {
        return getInt("check." + checkKey + ".threshold", "CHECK_" + checkKey.toUpperCase() + "_THRESHOLD", defaultValue);
    }

    public String getCheckChannels(String checkKey) {
        return getString("check." + checkKey + ".channels", "CHECK_" + checkKey.toUpperCase() + "_CHANNELS");
    }

    public String getCheckQueues(String checkKey) {
        return getString("check." + checkKey + ".queues", "CHECK_" + checkKey.toUpperCase() + "_QUEUES");
    }

    public int getCheckCpuLimit() {
        return getInt("check.host_resources.cpu_limit", "CHECK_HOST_RESOURCES_CPU_LIMIT", 95);
    }
}
