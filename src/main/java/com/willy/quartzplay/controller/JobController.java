package com.willy.quartzplay.controller;

import com.willy.quartzplay.service.JobManagementService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping
    public ResponseEntity<List<JobDetailResponse>> listJobs() {
        return ResponseEntity.ok(jobManagementService.listJobs());
    }

    @PostMapping("/{name}/trigger")
    public ResponseEntity<JobResponse> trigger(@PathVariable String name) {
        jobManagementService.triggerJob(name);
        return ResponseEntity.ok(new JobResponse("triggered", name));
    }

    @PostMapping("/{name}/pause")
    public ResponseEntity<JobResponse> pause(@PathVariable String name) {
        jobManagementService.pauseJob(name);
        return ResponseEntity.ok(new JobResponse("paused", name));
    }

    @PostMapping("/{name}/resume")
    public ResponseEntity<JobResponse> resume(@PathVariable String name) {
        jobManagementService.resumeJob(name);
        return ResponseEntity.ok(new JobResponse("resumed", name));
    }

    @PostMapping("/{name}/interrupt")
    public ResponseEntity<JobResponse> interrupt(@PathVariable String name) {
        jobManagementService.interruptJob(name);
        return ResponseEntity.ok(new JobResponse("interrupted", name));
    }

    @PostMapping("/{name}/skip-next")
    public ResponseEntity<JobResponse> skipNext(@PathVariable String name) {
        jobManagementService.skipNextExecution(name);
        return ResponseEntity.ok(new JobResponse("skipped", name));
    }
}
