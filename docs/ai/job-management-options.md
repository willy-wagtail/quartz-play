# Job Management Capabilities -- Design Discussion

## Requirements

1. **Trigger** a job to run immediately (NOW)
2. **Interrupt** a job that has been running too long
3. **Pause and resume** a scheduled job
4. **Skip next** -- cancel exactly the next scheduled execution, then automatically resume

---

## Quartz Scheduler API for Each Requirement

### 1. Trigger a Job NOW

```java
scheduler.triggerJob(JobKey.jobKey("inventorySync", "DEFAULT"));
```

Internally, Quartz creates a one-shot `SimpleTrigger` with start time = now and repeat count = 0. It fires the existing `JobDetail` immediately and cleans up the trigger after.

Trigger behavior:

- **Fires regardless of pause state.** `triggerJob()` creates its own one-shot trigger, independent of the cron trigger. If the cron trigger is paused, it stays paused -- the manual trigger fires anyway. This is correct: the user explicitly asked to run it now.
- **Fires regardless of the cron schedule.** The one-shot trigger is independent of the cron trigger. Both can coexist. The cron schedule is unaffected.
- **Rejects if the job is already running.** Quartz's default behavior with `@DisallowConcurrentExecution` is to silently queue the trigger and wait. We don't want that -- silent queuing is surprising and hard to debug. Instead, the controller should check `scheduler.getCurrentlyExecutingJobs()` for a matching `JobKey` **before** calling `triggerJob()`. If the job is already running, return `409 Conflict` immediately.

### 2. Interrupt a Long-Running Job

```java
scheduler.interrupt(JobKey.jobKey("inventorySync", "DEFAULT"));
```

This calls `InterruptableJob.interrupt()` on the currently executing job instance. The job **must** implement `InterruptableJob` (not just `Job`) or Quartz throws `UnableToInterruptJobException`.

This is cooperative cancellation -- Quartz does NOT forcibly kill the thread. The job's `interrupt()` method sets a flag, and `execute()` must check that flag periodically and bail out.

**Clustering limitation:** `scheduler.interrupt()` only checks the **local** scheduler instance. If the job is running on a different node in a cluster, the call returns `false` and nothing happens. There is no cross-cluster interrupt in Quartz. `triggerJob`, `pauseJob`, and `resumeJob` all work across the cluster because they write to the shared database -- interrupt is the exception. For this single-node playground project this is fine, but it would be a real problem in production.

See "Changes Required for Interrupt Support" below.

### 3. Pause and Resume

```java
scheduler.pauseJob(JobKey.jobKey("inventorySync", "DEFAULT"));   // pauses all triggers for the job
scheduler.resumeJob(JobKey.jobKey("inventorySync", "DEFAULT"));  // resumes them
```

Sets the trigger state to `PAUSED` in the `QRTZ_TRIGGERS` table. The scheduler's trigger-scan loop skips paused triggers entirely. On resume, the next fire time is recalculated from the current moment.

Pausing an already-paused job is a no-op. Resuming an already-active job is a no-op. Both are safe to call repeatedly.

### 4. Skip Next Execution

Quartz has no built-in "skip exactly the next fire" API. Pause/resume requires two calls and risks the user forgetting to resume.

The clean solution is a **`TriggerListener` with a veto.** Quartz's `TriggerListener` interface has a `vetoJobExecution()` method called right before a trigger fires. If it returns `true`, the trigger still fires (so the next fire time recalculates normally), but the job **does not execute**. Exactly "skip once, then carry on."

**Flow:**

1. User calls `POST /api/jobs/inventorySync/skip-next`
2. Controller sets a per-job flag in a shared `ConcurrentHashMap<JobKey, AtomicBoolean>`
3. At 3:00 AM, the cron trigger fires. Quartz calls `vetoJobExecution()` on the listener
4. Listener checks the flag -- it's set, so returns `true` (veto), clears the flag, logs it
5. The cron trigger recalculates its next fire time to tomorrow 3:00 AM. No pause/resume needed
6. Next day, `vetoJobExecution()` checks the flag -- cleared, returns `false`, job runs normally

**Why this over pause/resume:**

| | Pause + Resume | TriggerListener veto |
|---|---|---|
| API calls | 2 (pause, then remember to resume) | 1 (skip-next) |
| Risk of forgetting to resume | Yes | None -- auto-clears after one skip |
| Cron schedule affected | Yes (trigger state changes to PAUSED in DB) | No (trigger stays NORMAL) |

**Restart caveat:** The skip flag lives in memory. If the app restarts before the next fire, the flag is lost and the job runs as normal. This is a safe default -- better to run than to silently skip. Acceptable for this playground.

**Calling skip-next when already set:** No-op, the flag is already set. Return `200 OK`.

---

## Approach: Custom REST Controller

A `@RestController` that injects the Quartz `Scheduler` and wraps each API call in an endpoint. The existing Actuator Quartz endpoint (`GET /actuator/quartz/...`) stays as-is for read-only monitoring -- the controller handles write operations only.

**Read vs write workflow:** Use the Actuator endpoint to inspect state, use the controller to change state:
- **Read** (is the job paused? when does it fire next?): `GET /actuator/quartz/jobs/DEFAULT/inventorySync`
- **Write** (trigger, pause, resume, interrupt, skip-next): `POST /api/jobs/inventorySync/{action}`

**API path design:** Quartz identifies jobs by a `JobKey` (group + name pair). Since this project only uses the `DEFAULT` group, we hardcode the group and use just `{name}` in the path. If we ever add groups, promoting it to a path variable is a one-line change.

**Endpoints:**

| HTTP Method | Path | Action | Scheduler API |
|---|---|---|---|
| `POST` | `/api/jobs/{name}/trigger` | Fire now | `triggerJob(jobKey)` |
| `POST` | `/api/jobs/{name}/pause` | Pause | `pauseJob(jobKey)` |
| `POST` | `/api/jobs/{name}/resume` | Resume | `resumeJob(jobKey)` |
| `POST` | `/api/jobs/{name}/interrupt` | Interrupt running | `scheduler.interrupt(jobKey)` |
| `POST` | `/api/jobs/{name}/skip-next` | Skip next scheduled fire | Sets veto flag on `SkipNextTriggerListener` |

Each endpoint is 3-5 lines: validate the job exists (`scheduler.checkExists(jobKey)`), call the method, return a response. The whole controller is ~50-70 lines.

Example `curl` calls:

```bash
# Check current state (read -- Actuator)
curl http://localhost:8080/actuator/quartz/jobs/DEFAULT/inventorySync

# Manage the job (write -- controller)
curl -X POST http://localhost:8080/api/jobs/inventorySync/trigger
curl -X POST http://localhost:8080/api/jobs/inventorySync/pause
curl -X POST http://localhost:8080/api/jobs/inventorySync/resume
curl -X POST http://localhost:8080/api/jobs/inventorySync/interrupt
curl -X POST http://localhost:8080/api/jobs/inventorySync/skip-next
```

---

## Response Contract

### Response body

Use a simple JSON object with `status` and `jobName`:

```json
{ "status": "triggered", "jobName": "inventorySync" }
```

Possible `status` values: `"triggered"`, `"paused"`, `"resumed"`, `"interrupted"`, `"skip_next_set"`.

No need for a DTO class -- a `Map<String, String>` is fine for this playground project.

### HTTP status codes and edge cases

| Scenario | HTTP Status | Response |
|---|---|---|
| Success (trigger, pause, resume) | `200 OK` | `{ "status": "triggered", ... }` |
| Success (interrupt, job was running) | `200 OK` | `{ "status": "interrupted", ... }` |
| Trigger called but job is already running | `409 Conflict` | `{ "status": "already_running", ... }` |
| Trigger while job is paused | `200 OK` | Fires via one-shot trigger, cron trigger stays paused |
| Interrupt called but job is not currently running | `409 Conflict` | `{ "status": "not_running", ... }` |
| Job name does not exist | `404 Not Found` | `{ "status": "not_found", ... }` |
| Pause an already-paused job | `200 OK` | No-op, Quartz handles this silently |
| Resume an already-active job | `200 OK` | No-op, Quartz handles this silently |
| Skip-next success | `200 OK` | `{ "status": "skip_next_set", ... }` |
| Skip-next when already set | `200 OK` | No-op, flag is already set |
| Quartz `SchedulerException` | `500 Internal Server Error` | `{ "status": "error", "message": "..." }` |

### Error handling

Use a `@ExceptionHandler` method inside the controller (or a `@ControllerAdvice` if we add more controllers later) to catch `SchedulerException` and return a clean 500 response. For the interrupt edge case (`scheduler.interrupt()` returns `false`), check the return value inline and return 409.

---

## Logging

The controller should log each action at `INFO` level so there's evidence of what happened beyond just the HTTP response:

```
INFO  JobController - Triggering job: inventorySync
INFO  JobController - Pausing job: inventorySync
INFO  JobController - Resuming job: inventorySync
INFO  JobController - Interrupting job: inventorySync
WARN  JobController - Interrupt requested but job not running: inventorySync
INFO  JobController - Skip-next set for job: inventorySync
INFO  SkipNextTriggerListener - Vetoing execution of job: inventorySync (skip-next was set)
```

This is how you debug "I hit the endpoint but nothing happened" in practice.

---

## Changes Required for Interrupt Support

The current `InventorySyncJob` implements `Job`. To support `scheduler.interrupt()`, it must implement `InterruptableJob`.

The simplest approach: use **Java's built-in thread interruption** rather than a custom flag + `BooleanSupplier`. The current `sync()` method is a `Thread.sleep(500)` -- thread interruption works with this naturally since `sleep()` throws `InterruptedException` when the thread is interrupted. No API changes to `InventorySyncService` needed.

**Current:**
```java
public class InventorySyncJob implements Job {
    public void execute(JobExecutionContext context) throws JobExecutionException {
        inventorySyncService.sync();
    }
}
```

**After:**
```java
public class InventorySyncJob implements InterruptableJob {

    private volatile Thread runningThread;

    @Override
    public void interrupt() {
        Thread thread = this.runningThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        this.runningThread = Thread.currentThread();
        try {
            inventorySyncService.sync();
        } catch (InterruptedException e) {
            log.info("Job interrupted: inventorySync");
        } catch (Exception e) {
            throw new JobExecutionException("Inventory sync failed", e);
        } finally {
            // Clear the interrupt flag before returning the thread to the Quartz pool,
            // so a late-arriving interrupt() call doesn't affect the next job on this thread.
            Thread.interrupted();
            this.runningThread = null;
        }
    }
}
```

Key points:

- `interrupt()` and `execute()` run on different threads, so `runningThread` must be `volatile`
- `interrupt()` calls `Thread.interrupt()` on the worker thread -- this causes `Thread.sleep()` in the service to throw `InterruptedException` immediately
- **Separate catch for `InterruptedException`:** An interrupt is intentional, not a failure. Log at INFO, don't wrap it in `JobExecutionException`. This keeps it distinct from real errors.
- **`Thread.interrupted()` in finally:** Clears the interrupt flag before the thread returns to the Quartz pool. Without this, a late-arriving `interrupt()` call (race between `interrupt()` and `finally`) could leave the flag set on a thread that's now executing a different job.
- Since the job uses `@DisallowConcurrentExecution`, there is at most one running instance to interrupt -- no ambiguity
- If in the future `sync()` does real batch work (HTTP calls, DB iterations), the service can check `Thread.currentThread().isInterrupted()` at safe points. No Quartz-specific abstractions leak into the service layer.

### Service change: let `InterruptedException` propagate

The current `InventorySyncService.sync()` catches and swallows `InterruptedException`:

```java
public void sync() {
    log.info("Syncing inventory...");
    try {
        Thread.sleep(500);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();  // re-sets the flag but continues
    }
    log.info("Inventory sync complete.");  // still runs after interrupt!
}
```

This must change: declare `throws InterruptedException` and let it propagate so the job can handle it. Also, increase the sleep duration so interrupt is actually testable -- 500ms is too fast to hit with a manual curl.

```java
public void sync() throws InterruptedException {
    log.info("Syncing inventory...");
    Thread.sleep(10_000);  // 10 seconds -- long enough to interrupt manually
    log.info("Inventory sync complete.");
}
```

---

## Files to Create/Modify

| File | Change |
|---|---|
| `controller/JobController.java` | **New** -- REST controller with 5 endpoints, logging, error handling |
| `listener/SkipNextTriggerListener.java` | **New** -- `TriggerListener` that vetoes one execution per job when flag is set |
| `config/QuartzConfig.java` | Register `SkipNextTriggerListener` with the scheduler |
| `job/InventorySyncJob.java` | Change `Job` -> `InterruptableJob`, capture thread reference, separate catch for `InterruptedException`, clear interrupt flag in finally |
| `service/InventorySyncService.java` | Declare `throws InterruptedException`, increase sleep to 10s for testability |
