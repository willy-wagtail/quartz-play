package com.willy.quartzplay.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "QRTZ_FIRED_TRIGGERS")
@IdClass(QrtzFiredTriggerId.class)
public class QrtzFiredTrigger {

    @Id
    @Column(name = "SCHED_NAME")
    private String schedName;

    @Id
    @Column(name = "ENTRY_ID")
    private String entryId;

    @Column(name = "INSTANCE_NAME", nullable = false)
    private String instanceName;

    @Column(name = "JOB_NAME")
    private String jobName;

    @Column(name = "JOB_GROUP")
    private String jobGroup;

    @Column(name = "STATE", nullable = false)
    private String state;

    public String getSchedName() { return schedName; }
    public String getEntryId() { return entryId; }
    public String getInstanceName() { return instanceName; }
    public String getJobName() { return jobName; }
    public String getJobGroup() { return jobGroup; }
    public String getState() { return state; }
}
