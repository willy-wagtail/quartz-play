package com.willy.quartzplay.controller;

import com.willy.quartzplay.job.GroupName;
import com.willy.quartzplay.job.JobName;
import com.willy.quartzplay.service.JobManagementService;
import org.quartz.SchedulerException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobManagementService jobManagementService;

    public JobController(JobManagementService jobManagementService) {
        this.jobManagementService = jobManagementService;
    }

    @PostMapping("/{name}/trigger")
    public ResponseEntity<JobResponse> trigger(@PathVariable JobName name) throws SchedulerException {
        jobManagementService.triggerJob(name);
        return ResponseEntity.ok(new JobResponse("triggered", name.toString(), GroupName.DEFAULT.toString()));
    }
}
