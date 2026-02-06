package com.willy.quartzplay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jobs")
public class JobCronProperties {

    private String exampleJobCron;

    public String getExampleJobCron() {
        return exampleJobCron;
    }

    public void setExampleJobCron(String exampleJobCron) {
        this.exampleJobCron = exampleJobCron;
    }
}
