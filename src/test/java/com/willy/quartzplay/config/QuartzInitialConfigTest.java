package com.willy.quartzplay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class QuartzInitialConfigTest {

    private Scheduler scheduler;

    public static class NullJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {}
    }

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "OrphanTestScheduler");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown();
    }

    private ApplicationRunner buildRunner(List<JobDetail> jobDetails, List<Trigger> triggers) {
        JobCronProperties cronProperties = new JobCronProperties();
        QuartzInitialConfig config = new QuartzInitialConfig(cronProperties);
        return config.quartzSeedWarningRunner(scheduler, jobDetails, triggers);
    }

    @Test
    void orphanDetection_jobInSchedulerButNotInBeans_logsWarning(CapturedOutput output) throws Exception {
        // Seed a job directly into the scheduler (simulating an orphan left behind after bean removal)
        JobDetail orphan = JobBuilder.newJob(NullJob.class)
                .withIdentity("old-retired-job")
                .storeDurably()
                .build();
        scheduler.addJob(orphan, false);

        // Run with empty bean lists — everything in the scheduler is an orphan
        ApplicationRunner runner = buildRunner(List.of(), List.of());
        runner.run(null);

        assertThat(output).contains("Orphaned jobs in DB (no matching bean):");
        assertThat(output).contains("old-retired-job (NO_TRIGGERS)");
        assertThat(output).contains("Remove with DELETE /api/jobs/");
    }

    @Test
    void orphanDetection_orphanWithTrigger_reportsTriggerState(CapturedOutput output) throws Exception {
        JobDetail orphan = JobBuilder.newJob(NullJob.class)
                .withIdentity("firing-orphan")
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("firing-orphan-trigger")
                .forJob(orphan)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInHours(24)
                        .repeatForever())
                .startAt(DateBuilder.futureDate(1, DateBuilder.IntervalUnit.DAY))
                .build();
        scheduler.scheduleJob(orphan, trigger);

        ApplicationRunner runner = buildRunner(List.of(), List.of());
        runner.run(null);

        assertThat(output).contains("firing-orphan (NORMAL)");
    }

    @Test
    void orphanDetection_orphanWithPausedTrigger_reportsPausedState(CapturedOutput output) throws Exception {
        JobDetail orphan = JobBuilder.newJob(NullJob.class)
                .withIdentity("paused-orphan")
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("paused-orphan-trigger")
                .forJob(orphan)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInHours(24)
                        .repeatForever())
                .startAt(DateBuilder.futureDate(1, DateBuilder.IntervalUnit.DAY))
                .build();
        scheduler.scheduleJob(orphan, trigger);
        scheduler.pauseJob(JobKey.jobKey("paused-orphan"));

        ApplicationRunner runner = buildRunner(List.of(), List.of());
        runner.run(null);

        assertThat(output).contains("paused-orphan (PAUSED)");
    }

    @Test
    void orphanDetection_beanDefinedJob_notReportedAsOrphan(CapturedOutput output) throws Exception {
        JobDetail beanJob = JobBuilder.newJob(NullJob.class)
                .withIdentity("configured-job")
                .storeDurably()
                .build();
        scheduler.addJob(beanJob, false);

        // Pass the same job as a bean definition — should NOT be reported as orphan
        ApplicationRunner runner = buildRunner(List.of(beanJob), List.of());
        runner.run(null);

        assertThat(output).doesNotContain("Orphaned jobs");
    }

    @Test
    void orphanDetection_noOrphans_noWarning(CapturedOutput output) throws Exception {
        // Empty scheduler, empty beans — nothing to report
        ApplicationRunner runner = buildRunner(List.of(), List.of());
        runner.run(null);

        assertThat(output).doesNotContain("Orphaned jobs");
    }
}
