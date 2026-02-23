package com.willy.quartzplay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class QuartzPlayApplicationTests {

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private QuartzProperties quartzProperties;

    @Test
    void contextLoads() {
    }

    @Test
    void schedulerIsRunningWithCorrectName() throws Exception {
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(scheduler.getSchedulerName()).isEqualTo("QuartzPlayScheduler");
    }

    @Test
    void exampleJobIsRegistered() throws Exception {
        assertThat(scheduler.checkExists(JobKey.jobKey("example-job", "DEFAULT"))).isTrue();
    }

    @Test
    void overwriteExistingJobs_mustBeFalse() {
        assertThat(quartzProperties.isOverwriteExistingJobs()).isFalse();
    }

    @Test
    void clustering_mustBeEnabled() {
        assertThat(quartzProperties.getProperties())
                .containsEntry("org.quartz.jobStore.isClustered", "true");
    }
}
