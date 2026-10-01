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
        logger.info("Starting MQ Monitor Application...");

        // Load configuration
        ConfigLoader configLoader = new ConfigLoader();

        // Instantiate health check logic
        MqHealthCheck healthCheck = new MqHealthCheck(configLoader);

        // Fetch application port from environmental override, default to 8080
        int serverPort = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.trim().isEmpty()) {
            try {
                serverPort = Integer.parseInt(envPort.trim());
                logger.info("Server port overridden by env PORT to: {}", serverPort);
            } catch (NumberFormatException nfe) {
                logger.warn("Invalid env PORT value: '{}'. Defaulting to 8080.", envPort);
            }
        }

        // Initialize embedded Javalin web server
        int finalServerPort = serverPort;
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

        // Register GET /health/mq Endpoint
        app.get("/health/mq", ctx -> handleMqHealthCheck(ctx, healthCheck));

        // Start the server
        logger.info("Starting web server on port {}...", finalServerPort);
        app.start(finalServerPort);
        logger.info("Server successfully started and listening at http://localhost:{}", finalServerPort);
    }

    /**
     * Handler for the GET /health/mq endpoint. Executes health verification 
     * and maps results into the requested JSON formats and correct HTTP Status codes.
     */
    private static void handleMqHealthCheck(Context ctx, MqHealthCheck healthCheck) {
        MqHealthCheck.HealthStatus result = healthCheck.performCheck();
        
        ctx.contentType("application/json");

        if ("UP".equalsIgnoreCase(result.getStatus())) {
            ctx.status(200); // OK
        } else {
            ctx.status(503); // Service Unavailable for DOWN/DEGRADED
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
