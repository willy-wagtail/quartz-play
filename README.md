# quartz-play

Spring Boot Quartz playground with cluster-aware job management and Kafka-based interrupt broadcasting.

## Prerequisites

- Java 25
- Docker (for local Kafka via Spring Boot Docker Compose support)

## Running

```bash
./mvnw spring-boot:run
```

Docker Compose starts Kafka automatically on startup.

## API Endpoints

### Job Management

| Method | Endpoint | Description | Error Responses |
|--------|----------|-------------|-----------------|
| `GET` | `/api/jobs` | List all jobs with trigger info and running status | |
| `POST` | `/api/jobs/{name}/trigger` | Trigger a job immediately | 404 if job not found, 409 if already running |
| `POST` | `/api/jobs/{name}/pause` | Pause a job's triggers | 404 if job not found |
| `POST` | `/api/jobs/{name}/resume` | Resume a paused job's triggers | 404 if job not found |
| `POST` | `/api/jobs/{name}/interrupt` | Interrupt a running job (cluster-aware via Kafka) | 404 if job not found, 409 if not running |

### Actuator

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/actuator/health` | Application health check |
| `GET` | `/actuator/info` | Application info |
| `GET` | `/actuator/quartz` | Quartz scheduler summary |
| `GET` | `/actuator/quartz/jobs` | All registered jobs |
| `GET` | `/actuator/quartz/jobs/{groupName}` | Jobs in a specific group |
| `GET` | `/actuator/quartz/jobs/{groupName}/{jobName}` | Details of a specific job |
| `GET` | `/actuator/quartz/triggers` | All triggers |
| `GET` | `/actuator/quartz/triggers/{groupName}` | Triggers in a specific group |
| `GET` | `/actuator/quartz/triggers/{groupName}/{triggerName}` | Details of a specific trigger |

### H2 Console

| Endpoint | Description |
|----------|-------------|
| `/h2-console` | Browser-based SQL console for the in-memory database |

Connect with JDBC URL `jdbc:h2:mem:quartzdb`, username `sa`, no password.
