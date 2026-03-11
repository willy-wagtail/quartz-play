package com.willy.quartzplay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jobs")
public class JobCronProperties {

    private JobSchedule exampleJob;
    private JobSchedule automationJob;

    public JobSchedule getExampleJob() {
        return exampleJob;
    }

    public void setExampleJob(JobSchedule exampleJob) {
        this.exampleJob = exampleJob;
    }

    public JobSchedule getAutomationJob() {
        return automationJob;
    }

    public void setAutomationJob(JobSchedule automationJob) {
        this.automationJob = automationJob;
    }

    public static class JobSchedule {

        private String cron;
        private String timezone;

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }
}
