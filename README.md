# IBM MQ High-Availability Health Monitor Agent & Dashboard

A production-grade, self-contained Java 17+ agent that exposes a REST health-checking API and serving an embedded HTML monitoring dashboard. This application is designed to run side-by-side with an IBM MQ Queue Manager and provide intelligent status signaling to an **F5 BIG-IP load balancer** to support high-availability pooling.

---

## Architecture and F5 Load Balancer Strategy

Unlike simple ping monitors, this agent segregates failures into two categories to prevent cascading cluster outages:

1. **Member Failures (HTTP 503 Down):**
   - *Scope:* Issues localized specifically to this server (e.g., dead TCP port listener, JVM crash, queue manager stopped, local `/var/mqm` disk partition full (>95%), TLS certificate expired).
   - *F5 Action:* The REST API returns **HTTP 503 Service Unavailable**. The F5 immediately detects the status failure and drops this server member from the active pool, routing new client traffic to healthy active cluster nodes.

2. **Pool Failures (HTTP 200 Degraded):**
   - *Scope:* Cluster-wide issues that impact all queue managers simultaneously (e.g., full application queues, DLQ backlogs, external LDAP/CONNAUTH directory outages).
   - *F5 Action:* The REST API returns **HTTP 200 OK** but with an explicit `"status": "DEGRADED"` JSON payload. This triggers alerts on the admin dashboard and operations boards but **keeps the member online in the F5 pool**. This prevents a cascading failover from taking the entire application cluster offline for non-node-local issues.

---

## 12 Coverage Checks Table

The agent runs **12 distinct, independent health checks** on each API call:

| Row | Check Name | Subsystem Checked | F5 Severity Option | Source Verifier |
|---|---|---|---|---|
| 1 | `listener` | TCP Listener Port | `member_failure` | Direct TCP socket probe |
| 2 | `qmgr_connect` | Basic Local MQ Manager | `member_failure` | Native `MQQueueManager` connection |
| 3 | `client_connect` | Full TLS / Security Auth Connect | `member_failure` | Client credentials and SSL validation |
| 4 | `max_channels` | Channels Stanza Configuration | `member_failure` | Local file reading of host `qm.ini` |
| 5 | `disk_space` | `/var/mqm` Storage Mount | `member_failure` | Host OS File System API |
| 6 | `cert_expiry` | Trust TLS Certificate Lifespan | `member_failure` | Direct load and parsing of Java Keystore |
| 7 | `test_queue` | Operational Put/Get Capability | `pool_failure` | Active `MQQueue.accessQueue` probe |
| 8 | `dlq_depth` | System Dead Letter Queue Depth | `pool_failure` | Depth inquiry (`SYSTEM.DEAD.LETTER.QUEUE`) |
| 9 | `app_queue_depth` | Application Message Capacity | `pool_failure` | Multi-queue capacity tracking |
| 10| `ldap` | Directory Auth Directory | `pool_failure` | external LDAP/CONNAUTH validation |
| 11| `channel_status` | Critical Sndr/Rcvr Channel Status | `pool_failure` | Core system channel state verification |
| 12| `host_resources` | CPU & Memory Utilization | `member_failure` | Operating System JVM management bean |

---

## Installation & Deployment

### Prerequisite
* Red Hat OpenJDK 17 LTS (or newer)
* Apache Maven

### 1. Build the Shaded Fat JAR
Run the following at the root folder of the project:
```bash
mvn clean package
```
This produces a single, fully-shaded, executable JAR: `target/mq-health-check-1.0.0.jar`.

### 2. Configure Environment Variables
Copy the sample environment variable template:
```bash
cp .env.sample .env
```
Edit `.env` to input your actual queue manager parameters, keystore credentials, and channel specifications.

### 3. Deploy the Systemd Service (Linux Hosts)
To run the agent as a background daemon that automatically restarts on crash, deploy the provided service unit:
```bash
# 1. Copy service unit descriptor
sudo cp mq-health-check.service /etc/systemd/system/

# 2. Setup your application directories
sudo mkdir -p /opt/mqmonitor
sudo cp target/mq-health-check-1.0.0.jar /opt/mqmonitor/
sudo cp .env /opt/mqmonitor/

# 3. Enable and start the service
sudo systemctl daemon-reload
sudo systemctl enable mq-health-check.service
sudo systemctl start mq-health-check.service
```

---

## IBM MQSC Setup

Apply the idempotent script located in `src/main/resources/setup.mqsc` to set up queues, set a 60s channel heartbeat, and map security mappings:
```bash
runmqsc QM1 < src/main/resources/setup.mqsc
```

---

## F5 BIG-IP Monitor Configuration

Run these commands inside the BIG-IP TMSH CLI to setup the health checking rules:
```bash
# Import tmsh configuration rules
tmsh -f f5-setup.conf
```

---

## Access & Diagnostics

Once running, access the services:
* **Interactive Web Dashboard:** [http://localhost:8080/](http://localhost:8080/)
* **Raw JSON Health REST API:** [http://localhost:8080/health/mq](http://localhost:8080/health/mq)

### Sample API Responses

**Normal Health (UP):**
```json
{
  "status": "UP",
  "queueManager": "QM1",
  "timestamp": "2026-10-01T15:10:00Z",
  "details": "All enabled health checks passed successfully.",
  "checks": [
    { "name": "listener", "status": "UP", "details": "TCP connection established successfully.", "severity": "member_failure" }
    ...
  ]
}
```

**Non-Fatal Pool Issue (DEGRADED - Returns HTTP 200):**
```json
{
  "status": "DEGRADED",
  "queueManager": "QM1",
  "timestamp": "2026-10-01T15:12:00Z",
  "details": "[dlq_depth WARN: WARNING: DLQ SYSTEM.DEAD.LETTER.QUEUE depth is currently: 15 (Alert Limit: 10)]",
  "checks": [
    { "name": "dlq_depth", "status": "DEGRADED", "details": "WARNING: DLQ depth is currently: 15 (Alert Limit: 10)", "severity": "pool_failure" }
    ...
  ]
}
```

**Fatal Node Issue (DOWN - Returns HTTP 503):**
```json
{
  "status": "DOWN",
  "queueManager": "QM1",
  "timestamp": "2026-10-01T15:14:00Z",
  "details": "[listener FAILED: Connection to port 1414 failed: Connection refused]",
  "checks": [
    { "name": "listener", "status": "DOWN", "details": "Connection to port 1414 failed: Connection refused", "severity": "member_failure" }
    ...
  ]
}
```
