# Plan: Skip-Next Scheduled Firing

## Context

We need a "skip-next" feature: an HTTP endpoint that skips the next scheduled cron firing of a job without pausing it permanently. The job continues on its regular schedule after the skipped firing.

**Restart problem:** With `overwrite-existing-jobs: true`, any trigger-based approach (`rescheduleJob`) or JobDataMap flag gets wiped on restart. We need the skip flag in its own DB table, decoupled from Quartz's schema.

## Design decisions (agreed)

- **TriggerListener veto** — don't modify the trigger. Use Quartz's `vetoJobExecution()` to suppress one firing.
- **DB-backed skip flag** — a minimal `job_skip` table (just `job_name` PK). Row exists = skip pending. Survives restarts and works across cluster nodes.
- **Only veto cron triggers** — manual triggers (`SimpleTrigger` from `scheduler.triggerJob()`) must not be vetoed. If skip-next is set and you manually trigger, the manual trigger fires and the skip stays pending for the next cron firing.
- **Paused job → 409 error** — paused triggers have no meaningful "next fire time" to skip.
- **Idempotent** — calling skip-next multiple times is a no-op if a row already exists.
- **No Kafka needed** — all nodes read the skip flag from the shared DB.

## Approach: TriggerListener + persistent skip flag

**Skip-next endpoint flow:**
1. Validate job exists and has cron triggers
2. Check triggers aren't paused → 409 if so
3. Insert row into `job_skip` table (if not already present)
4. Return 200

**TriggerListener veto flow (on every firing):**
1. Check if trigger is a `CronTrigger` — if not, return `false` (allow manual triggers through)
2. Check `job_skip` table for a row matching this job name
3. If row exists → delete it, log, return `true` (veto)
4. Otherwise → return `false` (allow)

## Files created/modified

### New: `src/main/java/com/willy/quartzplay/repository/SkipNextStorageAdapter.java`

Nullable infrastructure wrapper (James Shore's pattern). A single class with paired static factories:

- `SkipNextStorageAdapter.create(repository)` — production, backed by JPA
- `SkipNextStorageAdapter.createNull()` — test double, backed by in-memory `HashSet`

Internally uses a private `Persistence` interface with two embedded implementations (`JpaPersistence` and `InMemoryPersistence`). The constructor is private — callers always go through the factories. No separate interface or `JpaSkipNextStorageAdapter` class needed.

```java
public class SkipNextStorageAdapter {
    private final Persistence persistence;

    public static SkipNextStorageAdapter create(SkipNextRepository repository) {
        return new SkipNextStorageAdapter(new JpaPersistence(repository));
    }

    public static SkipNextStorageAdapter createNull() {
        return new SkipNextStorageAdapter(new InMemoryPersistence());
    }

    private SkipNextStorageAdapter(Persistence persistence) { ... }

    public boolean exists(String jobName) { ... }
    public void save(String jobName) { ... }
    public void delete(String jobName) { ... }

    // Private: Persistence interface, JpaPersistence, InMemoryPersistence
}
```

Supporting classes (package-private): `SkipNext` entity, `SkipNextRepository extends JpaRepository<SkipNext, String>`, `SkipNextStorageAdapterConfig` (wires the production bean via `SkipNextStorageAdapter.create(repository)`).

### New: `src/main/resources/db/changelog/001-create-job-skip-table.yaml`

Liquibase changeset. Single-column table: `job_name varchar(200) PK`. Included from `db.changelog-master.yaml`.

### New: `src/main/java/com/willy/quartzplay/listener/SkipNextTriggerListener.java`

```java
public class SkipNextTriggerListener implements TriggerListener {
    @Override
    public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
        // Only veto cron triggers — let manual triggers through
        if (!(trigger instanceof CronTrigger)) return false;
        // Check skip flag, delete if found, return true to veto
    }
}
```

### Modified: `src/main/java/com/willy/quartzplay/config/QuartzConfig.java`

- Added class-level comment explaining the interaction with `overwrite-existing-jobs=true`
- Registered `SkipNextTriggerListener` as a global trigger listener via `SchedulerFactoryBeanCustomizer`

### Modified: `src/main/java/com/willy/quartzplay/controller/JobController.java`

Added `POST /{name}/skip-next` endpoint returning `JobResponse("skipped", name)`.

### Modified: `src/main/java/com/willy/quartzplay/service/JobManagementService.java`

- Added `SkipNextStorageAdapter` as 4th constructor parameter
- Added `skipNextExecution(String jobName)` with validation
- Added exception classes: `JobPausedException` (409), `NoCronTriggersException` (409), `SkipNextException` (500)

### Modified: `src/test/java/com/willy/quartzplay/controller/JobControllerTest.java`

- Test config wires `SkipNextStorageAdapter.createNull()` as a bean — no separate test double class needed
- Added `RecordingJob` with `AtomicBoolean executed` flag
- Added `registerJobWithCronTrigger` helper
- Each skip-next test uses a unique job name to avoid cross-test state leakage
- 7 new test cases (see below)

## Why TriggerListener veto over rescheduleJob

| | `rescheduleJob` | `TriggerListener` veto |
|---|---|---|
| Trigger modified? | Yes — rebuilt with new `startAt` | No — trigger untouched |
| Survives restart? | No — `overwrite-existing-jobs: true` resets it | Yes — skip flag in separate table |
| Manual trigger affected? | No | No (listener checks `instanceof CronTrigger`) |
| Cluster-aware? | Yes (DB) | Yes (DB) |

## Test cases

| Test | Setup | HTTP | Asserts |
|------|-------|------|---------|
| `skipNext_jobWithCronTrigger_succeeds` | Cron job | POST skip-next → 200 | `jobSkipStore.exists()` is true |
| `skipNext_nonExistentJob_returns404` | none | POST skip-next → 404 | |
| `skipNext_pausedJob_returns409` | Paused cron job | POST skip-next → 409 | |
| `skipNext_jobWithNoTriggers_returns409` | Durable job | POST skip-next → 409 | |
| `skipNext_calledTwice_isIdempotent` | Cron job | POST skip-next × 2 → both 200 | Store still has one entry |
| `skipNext_vetoesNextCronFiring` | Set skip + every-second cron with `RecordingJob` | Cron fires → vetoed | `RecordingJob.executed` is false, skip row deleted |
| `skipNext_doesNotVetoManualTrigger` | Set skip + POST trigger | Manual trigger fires | `RecordingJob.executed` is true, skip row still present |

## Verification

```bash
./mvnw test -pl . -Dtest=JobControllerTest
```

All 23 tests pass (16 existing + 7 new). No Docker, no Kafka required.
