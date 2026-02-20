package com.willy.quartzplay.messaging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.willy.quartzplay.repository.FiredTriggerStorageAdapter;
import com.willy.quartzplay.repository.SkipNextStorageAdapter;
import com.willy.quartzplay.service.JobManagementService;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class JobInterruptConsumerTest {

    private Scheduler scheduler;
    private JobInterruptConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "ConsumerTestScheduler");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();

        objectMapper = JsonMapper.builder().build();

        var service = new JobManagementService(
                scheduler,
                FiredTriggerStorageAdapter.createNull(new ArrayList<>()),
                JobInterruptProducerAdapter.createNull(objectMapper, new ArrayList<>()),
                SkipNextStorageAdapter.createNull());

        consumer = new JobInterruptConsumer(service, objectMapper);

        BlockingInterruptableJob.reset();
        BlockingJob.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown();
    }

    @Test
    void onInterrupt_wrongJobName_doesNotInterrupt() throws Exception {
        startJob("real-job", BlockingInterruptableJob.class);
        assertThat(BlockingInterruptableJob.started.await(5, SECONDS)).isTrue();

        consumer.onInterrupt(interruptMessage("wrong-job", "DEFAULT", "any-fire-id"));

        assertThat(BlockingInterruptableJob.interrupted.get())
                .as("Job should not have been interrupted")
                .isFalse();

        BlockingInterruptableJob.release.countDown();
    }

    @Test
    void onInterrupt_wrongFireInstanceId_doesNotInterrupt() throws Exception {
        startJob("my-job", BlockingInterruptableJob.class);
        assertThat(BlockingInterruptableJob.started.await(5, SECONDS)).isTrue();

        consumer.onInterrupt(interruptMessage("my-job", "DEFAULT", "wrong-fire-id"));

        assertThat(BlockingInterruptableJob.interrupted.get())
                .as("Job should not have been interrupted")
                .isFalse();

        BlockingInterruptableJob.release.countDown();
    }

    @Test
    void onInterrupt_nonInterruptableJob_logsWarning(CapturedOutput output) throws Exception {
        startJob("non-interruptable", BlockingJob.class);
        assertThat(BlockingJob.started.await(5, SECONDS)).isTrue();

        String fireInstanceId = fireInstanceIdFor("non-interruptable");

        consumer.onInterrupt(interruptMessage("non-interruptable", "DEFAULT", fireInstanceId));

        assertThat(output).contains("does not implement InterruptableJob, cannot interrupt");

        BlockingJob.release.countDown();
    }

    // -- Test jobs --

    public static class BlockingInterruptableJob implements InterruptableJob {
        private static CountDownLatch started = new CountDownLatch(1);
        private static CountDownLatch release = new CountDownLatch(1);
        private static final AtomicBoolean interrupted = new AtomicBoolean(false);

        static void reset() {
            started = new CountDownLatch(1);
            release = new CountDownLatch(1);
            interrupted.set(false);
        }

        @Override
        public void execute(JobExecutionContext context) {
            started.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void interrupt() {
            interrupted.set(true);
            release.countDown();
        }
    }

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
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -- Helpers --

    private void startJob(String name, Class<? extends Job> jobClass) throws SchedulerException {
        JobDetail job = JobBuilder.newJob(jobClass).withIdentity(name).build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(name + "-trigger").forJob(job).startNow().build();
        scheduler.scheduleJob(job, trigger);
    }

    private String fireInstanceIdFor(String jobName) throws SchedulerException {
        return scheduler.getCurrentlyExecutingJobs().stream()
                .filter(ctx -> ctx.getJobDetail().getKey().getName().equals(jobName))
                .findFirst()
                .map(JobExecutionContext::getFireInstanceId)
                .orElseThrow();
    }

    private String interruptMessage(String jobName, String jobGroup, String fireInstanceId) {
        return objectMapper.writeValueAsString(
                new InterruptJobCommand(jobName, jobGroup, fireInstanceId));
    }
}
