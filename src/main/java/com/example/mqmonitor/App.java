package com.example.mqmonitor;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filename: App.java
 * Purpose: Main application entry point that initializes the Javalin embedded web server, 
 *          registers static dashboard assets, and exposes the REST health endpoints.
 * Sensitive-Data Handling: None. Configuration is managed delegatively by ConfigLoader.
 */
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("Starting HA MQ Monitor Application...");

        // Load configuration
        ConfigLoader configLoader = new ConfigLoader();

        // Log resolved configuration to aid diagnostics on different servers
        logger.info("========================================");
        logger.info("Resolved IBM MQ Connection Configuration:");
        logger.info("  MQ Host:          {}", configLoader.getMqHost());
        logger.info("  MQ Port:          {}", configLoader.getMqPort());
        logger.info("  MQ Channel:       {}", configLoader.getMqChannel());
        logger.info("  MQ Queue Manager: {}", configLoader.getMqQueueManager());
        logger.info("  MQ Test Queue:    {}", configLoader.getMqTestQueue());
        logger.info("  MQ DLQ Name:      {}", configLoader.getMqDlqName());
        logger.info("  MQ Username:      {}", configLoader.getMqUsername());
        logger.info("========================================");

        // Programmatically configure JSSE standard SSL/TLS system properties if keystore is defined
        String keystorePath = configLoader.getMqKeystorePath();
        String keystorePassword = configLoader.getMqKeystorePassword();
        if (keystorePath != null && !keystorePath.trim().isEmpty() && !"CHANGE_ME".equals(keystorePath)) {
            System.setProperty("javax.net.ssl.keyStore", keystorePath.trim());
            System.setProperty("javax.net.ssl.trustStore", keystorePath.trim());
            logger.info("Configured JSSE System Property: javax.net.ssl.keyStore/trustStore = {}", keystorePath);
            if (keystorePassword != null && !keystorePassword.trim().isEmpty() && !"CHANGE_ME".equals(keystorePassword)) {
                System.setProperty("javax.net.ssl.keyStorePassword", keystorePassword);
                System.setProperty("javax.net.ssl.trustStorePassword", keystorePassword);
            }
        }

        // Instantiate health check logic
        MqHealthCheck healthCheck = new MqHealthCheck(configLoader);

        // Fetch application port from ConfigLoader (defaults to 8080 or PORT env)
        int serverPort = configLoader.getAppPort();

        // Initialize embedded Javalin web server
        Javalin app = Javalin.create(config -> {
            // Configure classpath static files directory '/public' corresponding to src/main/resources/public
            config.staticFiles.add("/public");
            
            // Log path and client info for basic diagnostic support
            config.requestLogger.http((ctx, ms) -> {
                logger.debug("Request: {} {} completed in {}ms", ctx.method(), ctx.path(), ms);
            });
        });

        // Expose info endpoint for dashboard identity branding
        app.get("/api/info", ctx -> {
            ctx.status(200);
            ctx.json(new AppInfo(configLoader.getAppName(), configLoader.getAppEnv()));
        });

        // Register GET /health/mq Endpoint with F5-safe status code mapping
        app.get("/health/mq", ctx -> handleMqHealthCheck(ctx, healthCheck));

        // Start the server
        logger.info("Starting web server on port {}...", serverPort);
        app.start(serverPort);
        logger.info("Server successfully started and listening at http://localhost:{}", serverPort);
    }

    /**
     * Handler for the GET /health/mq endpoint. Executes health verification 
     * and maps results into the requested JSON formats and F5 load-balancing safe HTTP status codes:
     * - UP (200 OK)
     * - DEGRADED (200 OK) -> Keeps member in F5 pool, alerts only. Prevents total pool outage.
     * - DOWN (503 Service Unavailable) -> F5 immediately drops this unhealthy member.
     */
    private static void handleMqHealthCheck(Context ctx, MqHealthCheck healthCheck) {
        MqHealthCheck.HealthSummary result = healthCheck.performCheck();
        
        ctx.contentType("application/json");

        if ("UP".equalsIgnoreCase(result.status)) {
            ctx.status(200); // 200 OK
        } else if ("DEGRADED".equalsIgnoreCase(result.status)) {
            ctx.status(200); // 200 OK (Alert but keep member in the F5 load-balancer pool)
        } else {
            ctx.status(503); // 503 Service Unavailable (Remove this unhealthy member from F5 pool)
        }
        
        ctx.json(result);
    }

    /**
     * Simple DTO for exposing branding parameters.
     */
    public static class AppInfo {
        private final String name;
        private final String env;

        public AppInfo(String name, String env) {
            this.name = name;
            this.env = env;
        }

        public String getName() {
            return name;
        }

        public String getEnv() {
            return env;
        }
    }
}
