package com.willy.quartzplay.controller;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.willy.quartzplay.listener.SkipNextTriggerListener;
import com.willy.quartzplay.listener.TriggerOriginJobListener;
import com.willy.quartzplay.repository.SkipNextStorageAdapter;
import com.willy.quartzplay.service.JobManagementService;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(JobController.class)
@ExtendWith(OutputCaptureExtension.class)
class JobControllerTest {

    @Autowired MockMvcTester mockMvcTester;
    @Autowired Scheduler scheduler;
    @Autowired SkipNextStorageAdapter skipNextStorage;

    @BeforeEach
    void setUp() throws Exception {
        scheduler.clear();
        RecordingJob.reset();
    }

    @TestConfiguration
    static class Config {
        @Bean
        SkipNextStorageAdapter skipNextStorage() {
            return SkipNextStorageAdapter.createNull();
        }

        @Bean(destroyMethod = "shutdown")
        Scheduler scheduler(SkipNextStorageAdapter skipNextStorage) throws Exception {
            Properties props = new Properties();
            props.setProperty("org.quartz.scheduler.instanceName", "TestScheduler");
            props.setProperty("org.quartz.threadPool.threadCount", "1");
            props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            Scheduler s = new StdSchedulerFactory(props).getScheduler();
            s.getListenerManager().addJobListener(new TriggerOriginJobListener());
            s.getListenerManager().addTriggerListener(new SkipNextTriggerListener(skipNextStorage));
            s.start();
            return s;
        }

        @Bean
        JobManagementService jobManagementService(Scheduler scheduler, SkipNextStorageAdapter skipNextStorage) {
            return new JobManagementService(scheduler, null, null, skipNextStorage);
        }
    }

    // -- Null jobs: decoupled from any real job implementation --

    public static class NullJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            // no-op
        }
    }

    // Two latches synchronize the test thread with the scheduler worker thread:
    //   - "started": test awaits this to confirm the job is running before making assertions
    //   - "release": job awaits this so it stays running until the test says it can finish
    // reset() creates fresh latches since CountDownLatch can't be reused once it hits 0.
    public static class BlockingJob implements Job {
        private static CountDownLatch started = new CountDownLatch(1);
        private static CountDownLatch release = new CountDownLatch(1);

        static void reset() {
            started = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override
        public void execute(JobExecutionContext context) {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Records whether it was executed. Used by trigger listener tests to assert
    // that a job was vetoed (executed == false) or allowed through (executed == true).
    public static class RecordingJob implements Job {
        static final AtomicBoolean executed = new AtomicBoolean(false);

        static void reset() { executed.set(false); }

        @Override
        public void execute(JobExecutionContext context) {
            executed.set(true);
        }
    }

    // -- Helpers --

    private void registerJob(String name, Class<? extends Job> jobClass) throws SchedulerException {
        JobDetail job = JobBuilder.newJob(jobClass)
                .withIdentity(name)
                .storeDurably()
                .build();
        scheduler.addJob(job, false);
    }

    private void registerJobWithSimpleTrigger(String name, Class<? extends Job> jobClass)
            throws SchedulerException {
        JobDetail job = JobBuilder.newJob(jobClass)
                .withIdentity(name)
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(name + "-trigger")
                .forJob(job)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInHours(24)
                        .repeatForever())
                .startAt(DateBuilder.futureDate(1, DateBuilder.IntervalUnit.DAY))
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    private void registerJobWithImmediateTrigger(String name, Class<? extends Job> jobClass)
            throws SchedulerException {
        JobDetail job = JobBuilder.newJob(jobClass)
                .withIdentity(name)
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(name + "-trigger")
                .forJob(job)
                .startNow()
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    private void registerJobWithCronTrigger(String name, Class<? extends Job> jobClass, String cron)
            throws SchedulerException {
        registerJobWithCronTrigger(name, jobClass, cron, 0);
    }

    private void registerJobWithCronTrigger(String name, Class<? extends Job> jobClass, String cron,
                                            int startDelaySeconds) throws SchedulerException {
        JobDetail job = JobBuilder.newJob(jobClass)
                .withIdentity(name)
                .storeDurably()
                .build();
        var tb = TriggerBuilder.newTrigger()
                .withIdentity(name + "-trigger")
                .forJob(job)
                .withSchedule(CronScheduleBuilder.cronSchedule(cron));
        if (startDelaySeconds > 0) {
            tb.startAt(DateBuilder.futureDate(startDelaySeconds, DateBuilder.IntervalUnit.SECOND));
        }
        scheduler.scheduleJob(job, tb.build());
    }

    // -- Trigger tests --

    @Test
    void triggerJob_existingIdleJob_succeeds() throws Exception {
        registerJob("my-job", NullJob.class);

        assertThat(mockMvcTester.post().uri("/api/jobs/my-job/trigger"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("triggered");
    }

    @Test
    void triggerJob_nonExistentJob_returns404() {
        assertThat(mockMvcTester.post().uri("/api/jobs/no-such-job/trigger"))
                .hasStatus4xxClientError()
                .hasStatus(404);
    }

    @Test
    void triggerJob_alreadyRunningJob_returns409() throws Exception {
        BlockingJob.reset();
        registerJob("blocking-job", BlockingJob.class);

        // First trigger starts the job
        assertThat(mockMvcTester.post().uri("/api/jobs/blocking-job/trigger"))
                .hasStatusOk();

        // Wait for the job to actually start executing
        assertThat(BlockingJob.started.await(5, TimeUnit.SECONDS))
                .as("BlockingJob should have started")
                .isTrue();

        // Second trigger while still running → 409
        assertThat(mockMvcTester.post().uri("/api/jobs/blocking-job/trigger"))
                .hasStatus4xxClientError()
                .hasStatus(409);

        // Release the blocking job so the scheduler thread can clean up
        BlockingJob.release.countDown();
    }

    @Test
    void triggerJob_pausedJob_succeeds() throws Exception {
        registerJobWithSimpleTrigger("paused-job", NullJob.class);
        scheduler.pauseJob(JobKey.jobKey("paused-job"));

        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("paused-job-trigger")))
                .isEqualTo(Trigger.TriggerState.PAUSED);

        // triggerJob creates an ad-hoc one-time trigger — bypasses pause state
        assertThat(mockMvcTester.post().uri("/api/jobs/paused-job/trigger"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("triggered");
    }

    // -- Trigger origin listener tests --

    @Test
    void triggerJob_manual_logsTriggerOrigin(CapturedOutput output) throws Exception {
        BlockingJob.reset();
        registerJob("logged-job", BlockingJob.class);

        assertThat(mockMvcTester.post().uri("/api/jobs/logged-job/trigger"))
                .hasStatusOk();

        // jobToBeExecuted fires before execute(), so started latch guarantees the log is written
        assertThat(BlockingJob.started.await(5, TimeUnit.SECONDS))
                .as("BlockingJob should have started")
                .isTrue();
        assertThat(output).contains("logged-job: Manually triggered");

        BlockingJob.release.countDown();
    }

    @Test
    void triggerJob_scheduled_logsTriggerOrigin(CapturedOutput output) throws Exception {
        BlockingJob.reset();
        registerJobWithImmediateTrigger("scheduled-job", BlockingJob.class);

        assertThat(BlockingJob.started.await(5, TimeUnit.SECONDS))
                .as("BlockingJob should have started")
                .isTrue();
        assertThat(output).contains("scheduled-job: Triggered by scheduler");

        BlockingJob.release.countDown();
    }

    // -- Pause tests --

    @Test
    void pauseJob_jobWithTriggers_pausesTriggers() throws Exception {
        registerJobWithSimpleTrigger("pausable-job", NullJob.class);

        assertThat(mockMvcTester.post().uri("/api/jobs/pausable-job/pause"))
                .hasStatusOk();

        Trigger.TriggerState state =
                scheduler.getTriggerState(TriggerKey.triggerKey("pausable-job-trigger"));
        assertThat(state).isEqualTo(Trigger.TriggerState.PAUSED);
    }

    @Test
    void pauseJob_nonExistentJob_returns404() {
        assertThat(mockMvcTester.post().uri("/api/jobs/no-such-job/pause"))
                .hasStatus4xxClientError()
                .hasStatus(404);
    }

    @Test
    void pauseJob_durableJobWithNoTriggers_succeeds() throws Exception {
        registerJob("durable-job", NullJob.class);

        assertThat(mockMvcTester.post().uri("/api/jobs/durable-job/pause"))
                .hasStatusOk();
    }

    @Test
    void pauseJob_alreadyPausedJob_succeeds() throws Exception {
        registerJobWithSimpleTrigger("paused-job", NullJob.class);
        scheduler.pauseJob(JobKey.jobKey("paused-job"));

        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("paused-job-trigger")))
                .isEqualTo(Trigger.TriggerState.PAUSED);

        assertThat(mockMvcTester.post().uri("/api/jobs/paused-job/pause"))
                .hasStatusOk();

        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("paused-job-trigger")))
                .isEqualTo(Trigger.TriggerState.PAUSED);
    }

    @Test
    void pauseJob_runningJob_pausesTriggersButJobKeepsRunning() throws Exception {
        BlockingJob.reset();
        registerJobWithSimpleTrigger("running-job", BlockingJob.class);

        // Start the job
        assertThat(mockMvcTester.post().uri("/api/jobs/running-job/trigger"))
                .hasStatusOk();
        assertThat(BlockingJob.started.await(5, TimeUnit.SECONDS))
                .as("BlockingJob should have started")
                .isTrue();
        assertThat(scheduler.getCurrentlyExecutingJobs())
                .extracting(ctx -> ctx.getJobDetail().getKey().getName())
                .containsExactly("running-job");

        // Pause while running — succeeds, pauses triggers, but job keeps executing
        assertThat(mockMvcTester.post().uri("/api/jobs/running-job/pause"))
                .hasStatusOk();

        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("running-job-trigger")))
                .isEqualTo(Trigger.TriggerState.PAUSED);
        assertThat(scheduler.getCurrentlyExecutingJobs())
                .as("Job should still be running")
                .isNotEmpty();

        BlockingJob.release.countDown();
    }

    // -- Resume tests --

    @Test
    void resumeJob_pausedJob_resumesTriggers() throws Exception {
        registerJobWithSimpleTrigger("resumable-job", NullJob.class);
        scheduler.pauseJob(JobKey.jobKey("resumable-job"));

        // Verify it's paused first
        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("resumable-job-trigger")))
                .isEqualTo(Trigger.TriggerState.PAUSED);

        assertThat(mockMvcTester.post().uri("/api/jobs/resumable-job/resume"))
                .hasStatusOk();

        Trigger.TriggerState state =
                scheduler.getTriggerState(TriggerKey.triggerKey("resumable-job-trigger"));
        assertThat(state).isNotEqualTo(Trigger.TriggerState.PAUSED);
    }

    @Test
    void resumeJob_alreadyResumedJob_succeeds() throws Exception {
        registerJobWithSimpleTrigger("active-job", NullJob.class);

        // Trigger state is NORMAL (not paused) — resuming is a no-op
        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("active-job-trigger")))
                .isEqualTo(Trigger.TriggerState.NORMAL);

        assertThat(mockMvcTester.post().uri("/api/jobs/active-job/resume"))
                .hasStatusOk();

        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("active-job-trigger")))
                .isEqualTo(Trigger.TriggerState.NORMAL);
    }

    @Test
    void resumeJob_runningJob_resumesTriggersAndJobKeepsRunning() throws Exception {
        BlockingJob.reset();
        registerJobWithSimpleTrigger("running-job", BlockingJob.class);

        // Start the job, then pause its triggers
        assertThat(mockMvcTester.post().uri("/api/jobs/running-job/trigger"))
                .hasStatusOk();
        assertThat(BlockingJob.started.await(5, TimeUnit.SECONDS))
                .as("BlockingJob should have started")
                .isTrue();
        scheduler.pauseJob(JobKey.jobKey("running-job"));

        // Resume while running — succeeds, un-pauses triggers, job keeps executing
        assertThat(mockMvcTester.post().uri("/api/jobs/running-job/resume"))
                .hasStatusOk();

        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("running-job-trigger")))
                .isNotEqualTo(Trigger.TriggerState.PAUSED);
        assertThat(scheduler.getCurrentlyExecutingJobs())
                .as("Job should still be running")
                .isNotEmpty();

        BlockingJob.release.countDown();
    }

    @Test
    void resumeJob_durableJobWithNoTriggers_succeeds() throws Exception {
        registerJob("durable-job", NullJob.class);

        assertThat(mockMvcTester.post().uri("/api/jobs/durable-job/resume"))
                .hasStatusOk();
    }

    @Test
    void resumeJob_nonExistentJob_returns404() {
        assertThat(mockMvcTester.post().uri("/api/jobs/no-such-job/resume"))
                .hasStatus4xxClientError()
                .hasStatus(404);
    }

    // -- Skip-next endpoint tests --

    @Test
    void skipNext_jobWithCronTrigger_savesSkipFlag() throws Exception {
        registerJobWithCronTrigger("cron-job", NullJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.post().uri("/api/jobs/cron-job/skip-next"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("skipped");

        assertThat(skipNextStorage.exists("cron-job")).isTrue();
    }

    @Test
    void skipNext_nonExistentJob_returns404() {
        assertThat(mockMvcTester.post().uri("/api/jobs/no-such-job/skip-next"))
                .hasStatus4xxClientError()
                .hasStatus(404);
    }

    @Test
    void skipNext_pausedJob_returns409() throws Exception {
        registerJobWithCronTrigger("paused-cron-job", NullJob.class, "0 0 0 * * ?");
        scheduler.pauseJob(JobKey.jobKey("paused-cron-job"));

        assertThat(mockMvcTester.post().uri("/api/jobs/paused-cron-job/skip-next"))
                .hasStatus4xxClientError()
                .hasStatus(409);
    }

    @Test
    void skipNext_durableJobWithNoTriggers_returns409() throws Exception {
        registerJob("durable-job", NullJob.class);

        assertThat(mockMvcTester.post().uri("/api/jobs/durable-job/skip-next"))
                .hasStatus4xxClientError()
                .hasStatus(409);
    }

    @Test
    void skipNext_jobWithOnlySimpleTrigger_returns409() throws Exception {
        registerJobWithSimpleTrigger("simple-trigger-job", NullJob.class);

        assertThat(mockMvcTester.post().uri("/api/jobs/simple-trigger-job/skip-next"))
                .hasStatus4xxClientError()
                .hasStatus(409);
    }

    @Test
    void skipNext_calledTwice_isIdempotent() throws Exception {
        registerJobWithCronTrigger("idempotent-cron-job", NullJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.post().uri("/api/jobs/idempotent-cron-job/skip-next"))
                .hasStatusOk();
        assertThat(mockMvcTester.post().uri("/api/jobs/idempotent-cron-job/skip-next"))
                .hasStatusOk();

        assertThat(skipNextStorage.exists("idempotent-cron-job")).isTrue();
    }

    // -- Cancel skip-next tests --

    @Test
    void cancelSkipNext_existingSkipFlag_deletesFlag() throws Exception {
        registerJobWithCronTrigger("cron-job", NullJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.post().uri("/api/jobs/cron-job/skip-next"))
                .hasStatusOk();
        assertThat(skipNextStorage.exists("cron-job")).isTrue();

        assertThat(mockMvcTester.delete().uri("/api/jobs/cron-job/skip-next"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("skip-cancelled");

        assertThat(skipNextStorage.exists("cron-job")).isFalse();
    }

    @Test
    void cancelSkipNext_noSkipFlagSet_isIdempotent() throws Exception {
        registerJobWithCronTrigger("cron-job", NullJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.delete().uri("/api/jobs/cron-job/skip-next"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("skip-cancelled");
    }

    @Test
    void cancelSkipNext_nonExistentJob_returns404() {
        assertThat(mockMvcTester.delete().uri("/api/jobs/no-such-job/skip-next"))
                .hasStatus4xxClientError()
                .hasStatus(404);
    }

    @Test
    void cancelSkipNext_pausedJob_succeeds() throws Exception {
        registerJobWithCronTrigger("paused-cron-job", NullJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.post().uri("/api/jobs/paused-cron-job/skip-next"))
                .hasStatusOk();
        assertThat(skipNextStorage.exists("paused-cron-job")).isTrue();

        scheduler.pauseJob(JobKey.jobKey("paused-cron-job"));

        assertThat(mockMvcTester.delete().uri("/api/jobs/paused-cron-job/skip-next"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("skip-cancelled");

        assertThat(skipNextStorage.exists("paused-cron-job")).isFalse();
    }

    // -- Skip-next status tests --

    @Test
    void getSkipNextStatus_skipFlagSet_returnsSkipPending() throws Exception {
        registerJobWithCronTrigger("cron-job", NullJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.post().uri("/api/jobs/cron-job/skip-next"))
                .hasStatusOk();

        assertThat(mockMvcTester.get().uri("/api/jobs/cron-job/skip-next"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("skip-pending");
    }

    @Test
    void getSkipNextStatus_noSkipFlag_returnsSkipNotPending() throws Exception {
        registerJobWithCronTrigger("fresh-cron-job", NullJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.get().uri("/api/jobs/fresh-cron-job/skip-next"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("status").asString().isEqualTo("skip-not-pending");
    }

    @Test
    void getSkipNextStatus_nonExistentJob_returns404() {
        assertThat(mockMvcTester.get().uri("/api/jobs/no-such-job/skip-next"))
                .hasStatus4xxClientError()
                .hasStatus(404);
    }

    // -- Skip-next trigger listener tests --

    @Test
    void skipNext_vetoesNextCronFiring(CapturedOutput output) throws Exception {
        // Delay start so the skip flag is set before the first cron firing
        registerJobWithCronTrigger("veto-job", RecordingJob.class, "* * * * * ?", 2);

        assertThat(mockMvcTester.post().uri("/api/jobs/veto-job/skip-next"))
                .hasStatusOk();

        // Wait for the skip flag to be consumed (confirming the veto happened)
        await().atMost(3, SECONDS).until(() -> !skipNextStorage.exists("veto-job"));

        // Immediately pause to prevent the next cron firing from executing the job
        scheduler.pauseJob(JobKey.jobKey("veto-job"));

        assertThat(skipNextStorage.exists("veto-job"))
                .as("Skip flag should be deleted after veto")
                .isFalse();
        assertThat(RecordingJob.executed.get())
                .as("RecordingJob should have been vetoed")
                .isFalse();
        assertThat(output).contains("Vetoing execution of job: veto-job");
    }

    @Test
    void skipNext_doesNotVetoManualTrigger() throws Exception {
        registerJobWithCronTrigger("manual-ok", RecordingJob.class, "0 0 0 * * ?");

        assertThat(mockMvcTester.post().uri("/api/jobs/manual-ok/skip-next"))
                .hasStatusOk();

        // Manual trigger via endpoint creates a SimpleTrigger — listener should not veto it
        assertThat(mockMvcTester.post().uri("/api/jobs/manual-ok/trigger"))
                .hasStatusOk();

        await().atMost(3, SECONDS).untilTrue(RecordingJob.executed);
        assertThat(skipNextStorage.exists("manual-ok"))
                .as("Skip flag should still be present for next cron firing")
                .isTrue();
    }

    @Test
    void skipNext_afterVeto_subsequentCronFiresNormally() throws Exception {
        // Delay start so the skip flag is set before the first cron firing
        registerJobWithCronTrigger("resume-after-skip", RecordingJob.class, "* * * * * ?", 2);

        assertThat(mockMvcTester.post().uri("/api/jobs/resume-after-skip/skip-next"))
                .hasStatusOk();

        // Wait for the skip flag to be consumed (first firing vetoed)
        await().atMost(3, SECONDS).until(() -> !skipNextStorage.exists("resume-after-skip"));

        // Wait for the next cron firing to execute normally
        await().atMost(3, SECONDS).untilTrue(RecordingJob.executed);

        scheduler.pauseJob(JobKey.jobKey("resume-after-skip"));

        assertThat(RecordingJob.executed.get())
                .as("Job should execute normally after skip is consumed")
                .isTrue();
    }
}
