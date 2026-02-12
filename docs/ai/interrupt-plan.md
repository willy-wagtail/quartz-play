# Plan: Interrupt Running Job

> Save this plan to `docs/ai/interrupt-plan.md` before implementing.

## Context

We have trigger, pause, and resume endpoints working. The next feature is interrupting a currently running job. This is **cooperative cancellation** -- Quartz doesn't forcibly kill threads. The job must opt in by implementing `InterruptableJob` and responding to the interrupt signal.

## How it works

1. Client calls `POST /api/jobs/example-job/interrupt`
2. `JobManagementService` calls `scheduler.interrupt(jobKey)`
3. Quartz finds the running job instance and calls its `interrupt()` method
4. `interrupt()` calls `Thread.interrupt()` on the worker thread
5. The service's `Thread.sleep()` (or any blocking I/O) throws `InterruptedException`
6. The exception propagates up to `ExampleJob.execute()`, which catches it and exits cleanly
7. The thread's interrupt flag is cleared in a `finally` block before returning the thread to the pool

## Files to change

### 1. `ExampleJobService.java` -- let InterruptedException propagate

Remove the try/catch around `Thread.sleep()`. Declare `throws InterruptedException`. Increase sleep to 10 seconds so there's time to hit the interrupt endpoint.

### 2. `ExampleJob.java` -- implement InterruptableJob

- Change `implements Job` → `implements InterruptableJob`
- Add `volatile Thread executingThread` field (volatile because `interrupt()` runs on a different thread)
- In `execute()`: capture `Thread.currentThread()`, delegate to service
- Catch `InterruptedException` separately from other exceptions (log at INFO, not a failure)
- In `finally`: clear interrupt flag with `Thread.interrupted()` and null out the thread reference
- In `interrupt()`: null-check the thread reference, then call `thread.interrupt()`

### 3. `JobManagementService.java` -- add interruptJob method

- `scheduler.interrupt(jobKey)` returns `boolean` -- true if delivered, false if job not running
- Use the return value directly rather than pre-checking `isAlreadyRunning()` (avoids race condition)
- If false → throw `JobNotRunningException` (409)
- Catch `UnableToInterruptJobException` separately -- Quartz throws this when the job implements `Job` but not `InterruptableJob` → throw `JobNotInterruptableException` (400)
- New exceptions:
  - `JobNotRunningException` (409)
  - `JobNotInterruptableException` (400)
  - `JobInterruptException` (500)

### 4. `JobController.java` -- add endpoint

- `POST /api/jobs/{name}/interrupt` → returns `JobResponse("interrupted", name)`

## Edge cases handled

| Scenario | Behaviour |
|---|---|
| Job not found | 404 via `JobNotFoundException` |
| Job exists but not running | 409 via `JobNotRunningException` |
| Job doesn't implement `InterruptableJob` | 400 via `JobNotInterruptableException` |
| Scheduler infrastructure failure | 500 via `JobInterruptException` |
| Interrupt arrives after job finishes | `interrupt()` null-checks the thread reference, no-op |
| Thread flag leaks to next job | Cleared in `finally` with `Thread.interrupted()` |
| Clustered mode | Only works on local node (known limitation) |

## Verification

1. Start the app
2. Trigger the job: `POST /api/jobs/example-job/trigger`
3. Within 10 seconds: `POST /api/jobs/example-job/interrupt`
4. Confirm logs show "Job interrupted" at INFO (not error)
5. Confirm the next scheduled cron fires normally
6. Test interrupt when NOT running → expect 409
7. Test interrupt on nonexistent job → expect 404
