package com.willy.quartzplay.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "SKIP_NEXT")
class SkipNextEntity {

    @Id
    @Column(name = "JOB_NAME", length = 200)
    private String jobName;

    protected SkipNextEntity() {}

    SkipNextEntity(String jobName) {
        this.jobName = jobName;
    }

    String getJobName() {
        return jobName;
    }
}
