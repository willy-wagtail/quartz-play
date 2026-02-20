package com.willy.quartzplay.controller;

import com.willy.quartzplay.service.JobManagementService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Spring Boot Actuator Quartz endpoints (expose via management.endpoints.web.exposure.include=quartz):
//   GET /actuator/quartz                          — summary of job and trigger counts per group
//   GET /actuator/quartz/jobs                     — all job groups and their job names
//   GET /actuator/quartz/jobs/{group}             — job names within a group
//   GET /actuator/quartz/jobs/{group}/{name}      — detail for a single job (class, triggers, data map)
//   GET /actuator/quartz/triggers                 — all trigger groups and their trigger names
//   GET /actuator/quartz/triggers/{group}         — trigger names within a group
//   GET /actuator/quartz/triggers/{group}/{name}  — detail for a single trigger (schedule, state, fire times)
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

    @PostMapping("/{name}/reschedule")
    public ResponseEntity<JobResponse> reschedule(@PathVariable String name,
                                                  @RequestBody RescheduleRequest request) {
        jobManagementService.rescheduleJob(name, request.cronExpression());
        return ResponseEntity.ok(new JobResponse("rescheduled", name));
    }

    @PostMapping("/{name}/skip-next")
    public ResponseEntity<JobResponse> skipNext(@PathVariable String name) {
        jobManagementService.skipNextExecution(name);
        return ResponseEntity.ok(new JobResponse("skipped", name));
    }

    @DeleteMapping("/{name}/skip-next")
    public ResponseEntity<JobResponse> cancelSkipNext(@PathVariable String name) {
        jobManagementService.cancelSkipNext(name);
        return ResponseEntity.ok(new JobResponse("skip-cancelled", name));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<JobResponse> delete(@PathVariable String name) {
        jobManagementService.deleteJob(name);
        return ResponseEntity.ok(new JobResponse("deleted", name));
    }

    @GetMapping("/{name}/skip-next")
    public ResponseEntity<JobResponse> getSkipNextStatus(@PathVariable String name) {
        boolean pending = jobManagementService.isSkipNextPending(name);
        return ResponseEntity.ok(new JobResponse(pending ? "skip-pending" : "skip-not-pending", name));
    }
}
