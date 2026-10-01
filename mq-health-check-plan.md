# IBM MQ Health Check API and Dashboard - Implementation Plan

This plan outlines the design and implementation of a self-contained, minimal Java application that exposes an IBM MQ Server Health Check via a REST API endpoint (`/health/mq`) and serves a lightweight HTML dashboard at the root (`/`).

---

## 1. Top-Level Overview

We will build a minimal, lightweight Java 17+ application with **Javalin** as the embedded web server and the **IBM MQ All Client JAR** (`com.ibm.mq.allclient`) for MQ connectivity. To minimize dependencies and complexity, we will avoid Spring Boot, Jakarta EE containers, or heavy frameworks.

### Architecture diagram

```
+--------------------------------------------------------+
|                      Client Browser                    |
|  - Renders dashboard on /                              |
|  - Calls GET /health/mq via fetch()                    |
+---------------------------+----------------------------+
                            | HTTP GET
                            v
+--------------------------------------------------------+
|                    Javalin Web Server                  |
|  - App.java: Main entrypoint & Router                  |
|  - ConfigLoader: Loads config.properties & env         |
+---------------------------+----------------------------+
                            | Call health check
                            v
+--------------------------------------------------------+
|                MqHealthCheck Logic                     |
|  - Creates & closes connection per check safely        |
|  - Parses MQExceptions for specific reason codes       |
+---------------------------+----------------------------+
                            | TCP / TLS
                            v
+--------------------------------------------------------+
|                     IBM MQ Server                      |
+--------------------------------------------------------+
```

---

## 2. Design Intent & Key Choices

1. **Web Server Framework:** We choose **Javalin** (v5.x or v6.x) as it runs exceptionally well on Java 17+, has an incredibly small footprint, is designed for modern Java lambdas, has built-in static file handling, and does not require complex annotations or container setups.
2. **Configuration Management:** We will use a standard Java `Properties` loader within `ConfigLoader.java`. It will load values from `src/main/resources/config.properties`, and then check for system environment variables (using naming matching standard conventions) to override these properties. This satisfies the requirement to have no hardcoded secrets and cleanly support `.env` files.
3. **MQ Connectivity & Lifecycle:** To verify health, the application will attempt to create an MQ connection, access/query the queue manager or open a test queue, and then immediately and cleanly close the connection. Since this is a health check endpoint, connections should be short-lived and closed within a `finally` block or `try-with-resources` to prevent resource leaks.
4. **Explicit Error Handling:** We will catch `MQException` specifically to log the MQ Completion Code (`completionCode`) and Reason Code (`reasonCode`). Other general exceptions (like connectivity errors or property loading errors) will be caught separately and translated into standard `DOWN` statuses with a 503 HTTP status.
5. **No Hardcoded Credentials:** The `config.properties` file will use `CHANGE_ME` placeholders for sensitive fields, and our code will load overrides from environment variables.

---

## 3. Sub-Tasks

### Sub-Task 1: Project Setup and Dependencies (`pom.xml`)
- **Intent:** Configure a standard Maven build targeting Java 17+ with minimal dependencies.
- **Expected Outcomes:** A clean, valid `pom.xml` with dependencies for Javalin, Jackson (for JSON serialization), SLF4J (for logging), and the IBM MQ All Client.
- **Relevant Files:** `pom.xml`
- **Status:** `[ ] pending`

### Sub-Task 2: Configuration Loader (`ConfigLoader.java`)
- **Intent:** Provide a utility to load configuration from `config.properties` and override values using environment variables.
- **Expected Outcomes:** A class `ConfigLoader` that exposes typed config values (e.g., host, port, channel, queueManager, testQueue, username, password) while ensuring sensitive values can be overridden from env.
- **Relevant Files:** `src/main/java/com/example/mqmonitor/ConfigLoader.java`, `src/main/resources/config.properties`, `.env.sample`
- **Status:** `[ ] pending`

### Sub-Task 3: MQ Health Check Service (`MqHealthCheck.java`)
- **Intent:** Implement the core IBM MQ connection and inquiry logic.
- **Expected Outcomes:** A class `MqHealthCheck` containing a `performCheck()` method that returns a structured HealthStatus object. It handles connection creation, a test queue open (or MQCMD inquiry), and guarantees connection closure in all code paths. It extracts specific MQ reason codes on failure.
- **Relevant Files:** `src/main/java/com/example/mqmonitor/MqHealthCheck.java`
- **Status:** `[ ] pending`

### Sub-Task 4: Main Application & REST API (`App.java`)
- **Intent:** Bootstraps Javalin, configures static file serving, and registers the GET `/health/mq` route.
- **Expected Outcomes:** An executable `App` class that initializes the server, serves `index.html` as static content, maps `/health/mq` to the `MqHealthCheck` results, and sets appropriate JSON response types and HTTP status codes (200 on UP/DEGRADED, 503 on DOWN).
- **Relevant Files:** `src/main/java/com/example/mqmonitor/App.java`
- **Status:** `[ ] pending`

### Sub-Task 5: Frontend Dashboard (`index.html`)
- **Intent:** Create a beautiful, responsive, single-page dashboard.
- **Expected Outcomes:** A lightweight HTML page with a modern design using CSS, featuring a "Check Status" button, color-coded badges, timestamp representation, and proper error handling with fetch.
- **Relevant Files:** `src/main/resources/public/index.html`
- **Status:** `[ ] pending`

### Sub-Task 6: Validation and Run Scripts
- **Intent:** Provide detailed documentation and commands to run, configure, and verify the application.
- **Expected Outcomes:** A complete "Build & Run" section in the output, demonstrating compilation, env copying, and execution.
- **Relevant Files:** N/A (Build instructions)
- **Status:** `[ ] pending`

---

## 4. Plan Validation

Please review the architectural choices and the outline. Let me know if:
1. You prefer properties over YAML (properties is chosen to keep dependencies to zero).
2. The choice of Javalin fits your vision.
3. The health check mechanism should perform a queue open (MQOO_INQUIRE/MQOO_OUTPUT) or if simple connection/disconnect is sufficient. (Queue open is highly recommended to verify queue access permissions and actual queue availability).
