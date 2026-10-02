# IBM MQ High-Availability Health Monitor Agent & Dashboard

A production-grade, self-contained Java 17+ agent that exposes a REST health-checking API and serves an embedded HTML monitoring dashboard. This application is designed to run side-by-side with an IBM MQ Queue Manager and provide intelligent status signaling to an **F5 BIG-IP load balancer** to support high-availability pooling.

---

## Overview

In enterprise application architectures, naive TCP port ping monitors can cause catastrophic cascading cluster outages. If a queue manager experiences a localized, non-fatal issue (such as a full application queue or a dead-letter queue backlog), dropping the entire node from the load-balancer pool forces the remaining healthy nodes to absorb the extra load, often cascading the failure across the rest of the cluster.

This application solves this problem by exposing a REST API endpoint (`/health/mq`) that performs **12 comprehensive subsystem health checks** and segregates their failure states into two F5-safe categories:
1. **Member Failures (HTTP 503 Down):** Critical, host-localized failures (e.g., dead TCP port listener, offline queue manager, JVM heap exhaustion, full `/var/mqm` local disk space, or expired SSL/TLS certificates). F5 drops this member from the active pool.
2. **Pool Failures (HTTP 200 Degraded):** Cluster-wide or logical warnings (e.g., application queue capacity over threshold, dead-letter queue backlog, or external directory/LDAP auth reachability failures). F5 **keeps the member active** to prevent cluster-wide cascading outages, but flags the degraded state to operators.

---

## How It Works

The application operates as a lightweight, continuous polling daemon with the following sequence:

### 1. Startup Sequence
- **Configuration Loading:** `ConfigLoader` initializes, first loading default static values from the classpath `config.properties`, then scanning the working directory for a `.env` file to apply overrides, and finally applying any OS-level system environment variables.
- **SSL/TLS System Property Injection:** If an MQ keystore path and password are provided, the system programmatically configures JSSE parameters (`javax.net.ssl.keyStore` and `javax.net.ssl.trustStore` along with passwords) into the JVM.
- **Diagnostics Output:** The resolved connection parameters (host, port, channel, queue manager, etc.) are printed to the console (excluding sensitive passwords).
- **Embedded Web Server Boot:** An embedded **Javalin** instance is initialized on the configured port (default `8080`), mounting static classpath assets from the `/public` directory (serving `index.html` as the default index route).

### 2. Request / Event Flow
```
[Client/F5 BIG-IP] 
       │
       │ HTTP GET /health/mq
       ▼
[Javalin Router] ──(Instantiates)──► [MqHealthCheck Engine]
                                               │
                                               ├─► 1. TCP Listener Check (Socket)
                                               ├─► 2. Queue Manager Basic Connect (No SSL)
                                               ├─► 3. Client Credentials Connect (With TLS)
                                               ├─► 4. Max Channels Check (qm.ini parse)
                                               ├─► 5. Disk Space Check (Local filesystem)
                                               ├─► 6. Certificate Expiry Check (JKS parse)
                                               ├─► 7. Test Queue PUT/GET (MQOO_INQUIRE/BROWSE)
                                               ├─► 8. DLQ Depth Inquiry (MQIA_CURRENT_Q_DEPTH)
                                               ├─► 9. Application Queues Depth Inquiry
                                               ├─► 10. LDAP/Directory Reachability Check
                                               ├─► 11. Channel Status Verification
                                               └─► 12. Host OS Resource Metrics
```

### 3. Key Processing Logic
* **On-Demand Checking:** Connections to IBM MQ are short-lived. To prevent resource leakage and handle connection degradation gracefully, connections are opened, queried, and closed cleanly using `finally` blocks during each health request.
* **F5 BIG-IP Safe Status Code Mapping:**
  * **`UP` (HTTP 200)**: All checks pass.
  * **`DEGRADED` (HTTP 200)**: A pool-level check (DLQ, App Queue depth, LDAP, or Channels) has failed.
  * **`DOWN` (HTTP 503)**: A member-level check (TCP port, QM connect, Disk Space, cert expiry, or host resources) has failed.

### 4. Cleanup and Shutdown
* Short-lived `MQQueueManager` and `MQQueue` references are closed in strict, guaranteed `finally` blocks within their respective methods to prevent network descriptor leaks.
* Javalin is managed as an embedded process that terminates cleanly upon receiving SIGTERM/SIGINT, cleaning up active Jetty socket listeners.

---

## Technology Stack

| Category | Technology | Notes |
|---|---|---|
| Runtime | Red Hat OpenJDK (Java 17 LTS minimum) | Supported JVM environment |
| Framework | Javalin 5.6.3 | Minimalist, virtual-thread-optimized (Loom) HTTP server |
| Container Web Server | Eclipse Jetty 11.0.17 | Shaded directly into the JAR via Javalin |
| Messaging / Events | IBM MQ All Client 9.3.4.0 (`com.ibm.mq.allclient`) | Native enterprise client connection library |
| Data / Serialization | Jackson Databind 2.15.2 | Handles automatic JSON serialization of DTO classes |
| Logging | SLF4J Simple 2.0.7 | Highly lightweight stdout log wrapper |
| Testing | JUnit Jupiter 5.9.3, Mockito Core 5.3.1 | Core unit validation suite |

---

## Project Structure

```
.
├── .env.sample                  # Sample environment variable template
├── .env                         # Actively used local configuration overrides (gitignored)
├── pom.xml                      # Maven project descriptor
├── f5-setup.conf                # TMSH commands to configure F5 monitors
├── mq-health-check.service      # Systemd service unit for Linux hosts
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com
│   │   │       └── example
│   │   │           └── mqmonitor
│   │   │               ├── App.java           # Entrypoint, route mappings, SSL property config
│   │   │               ├── ConfigLoader.java  # Environment/properties merger and .env filesystem parser
│   │   │               └── MqHealthCheck.java # Implements the 12 active subsystem verifications
│   │   └── resources
│   │       ├── config.properties              # Classpath default properties file
│   │       ├── setup.mqsc                     # Idempotent channel/queue definitions for MQ managers
│   │       └── public
│   │           └── index.html                 # Lightweight visual HTML health dashboard
│   └── test
│       └── java
│           └── com
│               └── example
│                   └── mqmonitor
│                       └── MqHealthCheckTest.java # Non-reflection unit test suite for pool logic
```

---

## Setup & Configuration

### Prerequisites
* Red Hat OpenJDK 17 LTS (or newer)
* Apache Maven 3.8+
* IBM MQ Queue Manager (v9.x or v10.x) with an open SVRCONN channel (e.g., `DEV.APP.SVRCONN` or `APP.SVRCONN`)

### Environment Variables (.env)
The application dynamically merges local files with system env variables. You can override any of these keys in your `.env` file:

| Variable | Purpose | Required | Safe Default |
|---|---|---|---|
| `PORT` | Local HTTP port for the web dashboard and REST API | Optional | `8080` |
| `APP_ENV` | Environment identifier shown on the dashboard | Optional | `Production` |
| `MQ_HOST` | Hostname or IP of the IBM MQ server | **Required** | `CHANGE_ME` |
| `MQ_PORT` | Port number of the IBM MQ Listener | Optional | `1414` |
| `MQ_CHANNEL` | Server connection channel name (SVRCONN) | Optional | `SYSTEM.DEF.SVRCONN` |
| `MQ_QUEUE_MANAGER` | Queue Manager name | Optional | `QM1` |
| `MQ_TEST_QUEUE` | Queue name to test active PUT/GET/INQUIRE capabilities | Optional | `DEV.QUEUE.1` |
| `MQ_DLQ_NAME` | System Dead Letter Queue name for depth check | Optional | `SYSTEM.DEAD.LETTER.QUEUE` |
| `MQ_INI_PATH` | Path to the local `qm.ini` (for max channels check) | Optional | `/var/mqm/qmgrs/QM1/qm.ini` |
| `MQ_DATA_PATH` | Directory mount to evaluate storage disk utilization | Optional | `/var/mqm` |
| `MQ_KEYSTORE_PATH` | Path to the JKS/PKCS12 Java Key Store | Optional | `CHANGE_ME` |
| `MQ_KEYSTORE_PASSWORD` | Password to unlock and inspect the JKS Key Store | Optional | `CHANGE_ME` |
| `MQ_USERNAME` | Security user ID for queue manager authentication | Optional | `CHANGE_ME` |
| `MQ_PASSWORD` | Security password for user ID authentication | Optional | `CHANGE_ME` |
| `MQ_SSL_CIPHER_SUITE` | SSL/TLS Cipher suite to enable on the client connection | Optional | (Plain TCP used if empty) |
| `CHECK_<NAME>_ENABLED` | Toggles any of the 12 checks (`true` or `false`) | Optional | `true` |

### Installation & Run Steps

#### 1. Compile the Shaded JAR
```bash
mvn clean package
```

#### 2. Run Locally with .env overrides
Create `.env` file from the template and run:
```bash
cp .env.sample .env
# Edit .env with your local values
java -jar target/mq-health-check-1.0.0.jar
```

#### 3. Run in Production (Systemd Service)
```bash
sudo cp mq-health-check.service /etc/systemd/system/
sudo mkdir -p /opt/mqmonitor
sudo cp target/mq-health-check-1.0.0.jar /opt/mqmonitor/
sudo cp .env /opt/mqmonitor/
sudo systemctl daemon-reload
sudo systemctl enable mq-health-check.service
sudo systemctl start mq-health-check.service
```

---

## API Reference

### 1. Health Status Endpoint
* **Path:** `GET /health/mq`
* **Purpose:** Evaluates all 12 subsystems and returns a consolidated health summary.
* **Query Parameters:** None.
* **Response Payload Shape (HTTP 200 or 503):**
  ```json
  {
    "status": "UP | DEGRADED | DOWN",
    "queueManager": "QM1",
    "timestamp": "2026-10-02T15:06:28.380970Z",
    "details": "All enabled health checks passed successfully.",
    "checks": [
      {
        "name": "listener",
        "status": "UP | DEGRADED | DOWN",
        "details": "TCP connection established successfully.",
        "severity": "member_failure"
      }
      // ... up to 12 checks
    ]
  }
  ```
* **Error States:** 
  * If any check with `severity: member_failure` fails, returns **HTTP 503 Service Unavailable** with the JSON payload above.
  * If only checks with `severity: pool_failure` fail, returns **HTTP 200 OK** (Status: `DEGRADED`) with the JSON payload above.

### 2. Identity Branding Endpoint
* **Path:** `GET /api/info`
* **Purpose:** Provides identity parameters to render metadata on the HTML dashboard.
* **Response Payload Shape (HTTP 200):**
  ```json
  {
    "name": "IBM MQ High-Availability Monitor",
    "env": "Production"
  }
  ```

---

## Testing

The project has a robust automated JUnit 5 test suite that avoids problematic Mockito-agent class mutations on Java 17+. Instead, it injects explicit properties into the configuration constructor to validate the F5 pool segregation logic.

### To execute tests:
```bash
mvn test
```
### Coverage:
* `testAllChecksPassing_ReturnsUP`: Validates that when all configured, enabled local file and system checks are functioning, status returns `UP`.
* `testPoolFailureOnly_ReturnsDEGRADED`: Simulates a non-fatal backend resource failure (e.g., unreachable test queue) and verifies the API returns `DEGRADED` (HTTP 200) instead of falling over.
* `testMemberFailureOccurs_ReturnsDOWN`: Simulates a localized node failure (e.g., unreachable IP listener) and verifies the API returns `DOWN` (HTTP 503) to trigger immediate pool removal.

---

## Known Limitations / TODOs

### Module: `MqHealthCheck`
* `max_channels` check parses the host `qm.ini` file's Channels stanza. In a true cluster-local deployment, this is a valid direct metric. However, for a pure remote-client topology, it operates on a simulated safe fallback logic if the file is unavailable.
* `channel_status` currently reports health based on the client connectivity channel. A true production improvement is to query dynamic channel state metrics directly using PCF commands (`MQCMD_INQUIRE_CHANNEL_STATUS`).
