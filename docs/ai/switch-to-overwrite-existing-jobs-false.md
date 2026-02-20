# Plan: Switch to `overwrite-existing-jobs: false`

## Context

With `overwrite-existing-jobs: true`, every node restart re-registers job/trigger beans, overwriting the DB. This loses runtime state like paused triggers. Approach C: turn off overwrite so the DB becomes the source of truth after first deployment. Bean definitions become "initial defaults" only. Config changes (e.g. cron expressions) are deployed via an admin endpoint instead.

## Change 1: Flip the flag

**Modify:** `src/main/resources/application.yaml`

- Change `overwrite-existing-jobs: true` → `false`

With this setting:
- **First startup**: jobs/triggers are created from beans (DB is empty)
- **Subsequent startups**: beans are skipped because jobs/triggers already exist — runtime state (pause, reschedule) is preserved
- **New jobs**: a new bean registers automatically on next startup (doesn't exist in DB yet)
- **Removed jobs**: remain in DB — delete via admin endpoint or Liquibase migration

## Impact on existing endpoints

No code changes needed — each endpoint already works correctly against the DB. The difference is their effects now **survive restarts**:

- **pause/resume**: Already set trigger state (`PAUSED`/`WAITING`) in `QRTZ_TRIGGERS`. Previously lost on restart because overwrite reset triggers. Now preserved.
- **trigger**: Fires a one-shot ad-hoc trigger. No dependency on overwrite. Unchanged.
- **interrupt**: Broadcasts via Kafka, acts on in-memory state. No dependency on overwrite. Unchanged.
- **skip-next**: Already uses a separate `SKIP_NEXT` table, so was never affected by overwrite. Unchanged. Keeping the separate table (not moving to `JobDataMap`) — it's cleaner for a toggle flag (simple INSERT/DELETE vs re-serializing the entire map), it's a better separation of concerns (application behavior, not scheduling metadata), and the adapter pattern with its null test implementation works well. Update the QuartzConfig and skip-next changelog comments to reflect the real rationale (separation of concerns) rather than the overwrite workaround.

## Change 2: Rename QuartzConfig and update comments

**Rename:** `QuartzConfig.java` → `QuartzInitialConfig.java`

- Rename class to `QuartzInitialConfig` to signal "initial setup, not ongoing config"
- Replace the existing comment block (lines 20-24) to explain: these beans are seed data for first deployment only, the DB is the source of truth after that, use the reschedule endpoint for cron changes
- Update the skip-next changelog (`001-create-skip-next-table.yaml`) comment to reflect the real rationale: separation of concerns (application behavior vs scheduling metadata), not the overwrite workaround

## Change 3: Add startup warning when beans are skipped

**Modify:** `src/main/java/com/willy/quartzplay/config/QuartzInitialConfig.java`

Add an `ApplicationRunner` bean that checks each defined job/trigger against the DB on startup. For each one that already exists, log a WARN:

```
Job 'example-job' already exists in DB — bean definition skipped. To change the schedule, use POST /api/jobs/{name}/reschedule
Trigger 'example-job' already exists in DB — bean definition skipped. To change the schedule, use POST /api/jobs/{name}/reschedule
```

This makes it immediately visible in logs that the beans aren't being applied, and points developers to the right mechanism for changes.

## Change 4: Add reschedule endpoint

**Modify:** `src/main/java/com/willy/quartzplay/service/JobManagementService.java`

Add `rescheduleJob(String jobName, String cronExpression)` that:
1. Validates the job exists and has a cron trigger
2. Captures whether the trigger is currently paused
3. Calls `scheduler.rescheduleJob()` with the new cron expression
4. Re-pauses the trigger if it was paused before

**Modify:** `src/main/java/com/willy/quartzplay/controller/JobController.java`

Add `POST /api/jobs/{name}/reschedule` that accepts a cron expression in the request body and delegates to the service.

**Create:** `src/main/java/com/willy/quartzplay/controller/RescheduleRequest.java`

Simple record: `record RescheduleRequest(String cronExpression) {}`

## Change 5: Add tests for reschedule

**Modify:** `src/test/java/com/willy/quartzplay/controller/JobControllerTest.java`

Tests:
- Reschedule with valid cron → 200, trigger updated
- Reschedule preserves paused state
- Reschedule non-existent job → 404
- Reschedule job with no cron trigger → 409
- Reschedule with invalid cron expression → 400

## Files summary

| Action | File |
|--------|------|
| MODIFY | `application.yaml` — flip `overwrite-existing-jobs` to `false` |
| RENAME | `config/QuartzConfig.java` → `QuartzInitialConfig.java` — rename class, update comments, add startup warning runner |
| MODIFY | `db/changelog/001-create-skip-next-table.yaml` — update comment rationale |
| MODIFY | `service/JobManagementService.java` — add `rescheduleJob` method |
| MODIFY | `controller/JobController.java` — add `POST /{name}/reschedule` endpoint |
| CREATE | `controller/RescheduleRequest.java` — request body record |
| MODIFY | `controller/JobControllerTest.java` — add reschedule tests |

## Verification

1. `./mvnw test` — all existing + new tests pass
2. Manual: start app, pause a job, restart app, verify job is still paused
