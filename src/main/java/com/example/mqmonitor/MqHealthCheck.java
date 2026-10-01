package com.example.mqmonitor;

import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.MQException;
import com.ibm.mq.constants.CMQC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Hashtable;

/**
 * Filename: MqHealthCheck.java
 * Purpose: Connects to the IBM MQ Server, accesses the test queue to perform a live health check, 
 *          and returns structured JSON-serializable health results with specific error code tracing.
 * Sensitive-Data Handling: Reads credentials securely via ConfigLoader (no hardcoding of sensitive properties).
 */
public class MqHealthCheck {
    private static final Logger logger = LoggerFactory.getLogger(MqHealthCheck.class);
    private final ConfigLoader config;

    public MqHealthCheck(ConfigLoader config) {
        this.config = config;
    }

    /**
     * Executes the health check of the MQ Queue Manager and the test queue.
     * Connection Lifecycle Choice: A short-lived connection is opened and closed programmatically 
     * on each request to verify actual, live end-to-end socket and queue availability, preventing 
     * stale connection states and resource leaks on the MQ server.
     */
    public HealthStatus performCheck() {
        String qmName = config.getMqQueueManager();
        String host = config.getMqHost();
        int port = config.getMqPort();
        String channel = config.getMqChannel();
        String testQueueName = config.getMqTestQueue();
        String username = config.getMqUsername();
        String password = config.getMqPassword();

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        // Validate basic configuration first
        if (host == null || host.trim().isEmpty() || "CHANGE_ME".equals(host)) {
            return new HealthStatus(
                "DOWN",
                qmName,
                timestamp,
                "Invalid configuration: mq.host is not properly set."
            );
        }

        // Setup MQ connection properties
        Hashtable<String, Object> props = new Hashtable<>();
        props.put(CMQC.HOST_NAME_PROPERTY, host);
        props.put(CMQC.PORT_PROPERTY, port);
        props.put(CMQC.CHANNEL_PROPERTY, channel);
        props.put(CMQC.TRANSPORT_PROPERTY, CMQC.TRANSPORT_MQSERIES_CLIENT);

        // Security Authentication
        if (username != null && !username.trim().isEmpty() && !"CHANGE_ME".equals(username)) {
            props.put(CMQC.USER_ID_PROPERTY, username);
        }
        if (password != null && !password.trim().isEmpty() && !"CHANGE_ME".equals(password)) {
            props.put(CMQC.PASSWORD_PROPERTY, password);
        }

        // SSL / TLS configuration if provided
        String sslCipherSuite = config.getMqSslCipherSuite();
        if (sslCipherSuite != null && !sslCipherSuite.trim().isEmpty()) {
            props.put(CMQC.SSL_CIPHER_SUITE_PROPERTY, sslCipherSuite.trim());
            logger.info("Configuring MQ connection to use SSL Cipher Suite: {}", sslCipherSuite);

            String keystorePath = config.getMqKeystorePath();
            String keystorePassword = config.getMqKeystorePassword();
            if (keystorePath != null && !keystorePath.trim().isEmpty()) {
                System.setProperty("javax.net.ssl.keyStore", keystorePath);
                if (keystorePassword != null) {
                    System.setProperty("javax.net.ssl.keyStorePassword", keystorePassword);
                }
            }
        }

        MQQueueManager qMgr = null;
        MQQueue testQueue = null;

        try {
            logger.info("Attempting to connect to MQ Queue Manager '{}' at {}:{} via channel '{}'", qmName, host, port, channel);
            qMgr = new MQQueueManager(qmName, props);
            logger.info("Connected successfully to MQ Queue Manager '{}'", qmName);

            // Attempt to open the test queue to verify operational permissions and queue state
            logger.info("Attempting to open test queue '{}' to verify health...", testQueueName);
            int openOptions = CMQC.MQOO_INQUIRE | CMQC.MQOO_FAIL_IF_QUIESCING;
            testQueue = qMgr.accessQueue(testQueueName, openOptions);
            logger.info("Successfully opened and verified test queue '{}'", testQueueName);

            return new HealthStatus(
                "UP",
                qmName,
                timestamp,
                "Successfully connected to Queue Manager '" + qmName + "' and verified test queue '" + testQueueName + "'."
            );

        } catch (MQException mqEx) {
            int compCode = mqEx.completionCode;
            int reasonCode = mqEx.reasonCode;
            String errorMsg = getFriendlyErrorMessage(compCode, reasonCode, mqEx.getMessage());
            
            logger.error("MQException during health check: Completion Code = {}, Reason Code = {}, Message = {}", 
                compCode, reasonCode, mqEx.getMessage(), mqEx);

            return new HealthStatus(
                "DOWN",
                qmName,
                timestamp,
                String.format("MQ Error: %s (CompCode: %d, Reason: %d)", errorMsg, compCode, reasonCode)
            );
        } catch (Exception ex) {
            logger.error("General Exception during MQ health check: {}", ex.getMessage(), ex);
            return new HealthStatus(
                "DOWN",
                qmName,
                timestamp,
                "General Exception: " + ex.getMessage()
            );
        } finally {
            // Clean up resources cleanly to prevent server socket/handle exhaustion
            if (testQueue != null) {
                try {
                    testQueue.close();
                    logger.debug("Closed MQ test queue successfully.");
                } catch (Exception ex) {
                    logger.error("Error closing test queue: {}", ex.getMessage());
                }
            }
            if (qMgr != null) {
                try {
                    qMgr.disconnect();
                    logger.info("Disconnected from MQ Queue Manager successfully.");
                } catch (Exception ex) {
                    logger.error("Error disconnecting from Queue Manager: {}", ex.getMessage());
                }
            }
        }
    }

    private String getFriendlyErrorMessage(int compCode, int reasonCode, String rawMsg) {
        switch (reasonCode) {
            case CMQC.MQRC_NOT_AUTHORIZED: // 2035
                return "Not authorized to connect or access resources. Please verify username/password and security settings.";
            case CMQC.MQRC_HOST_NOT_AVAILABLE: // 2538
                return "Host unreachable. Verify hostname, port, and network routes.";
            case CMQC.MQRC_Q_MGR_NOT_AVAILABLE: // 2059
                return "Queue Manager is not active or not running.";
            case CMQC.MQRC_Q_MGR_NAME_ERROR: // 2058
                return "Invalid Queue Manager name specified.";
            case CMQC.MQRC_UNKNOWN_OBJECT_NAME: // 2085
                return "The configured test queue does not exist on this Queue Manager.";
            case CMQC.MQRC_CONNECTION_BROKEN: // 2009
                return "The connection to the MQ host was broken.";
            case CMQC.MQRC_CHANNEL_NOT_AVAILABLE: // 2540
                return "The specified channel is not defined, inactive, or not accessible.";
            default:
                return rawMsg != null ? rawMsg : "Unknown MQ error occurred.";
        }
    }

    /**
     * Immutable DTO to represent the health check JSON payload.
     */
    public static class HealthStatus {
        private final String status;
        private final String queueManager;
        private final String timestamp;
        private final String details;

        public HealthStatus(String status, String queueManager, String timestamp, String details) {
            this.status = status;
            this.queueManager = queueManager;
            this.timestamp = timestamp;
            this.details = details;
        }

        public String getStatus() {
            return status;
        }

        public String getQueueManager() {
            return queueManager;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getDetails() {
            return details;
        }
    }
}
