package com.willy.quartzplay.controller;

import com.willy.quartzplay.service.JobManagementService.InvalidCronExpressionException;
import com.willy.quartzplay.service.JobManagementService.InvalidTimezoneException;
import com.willy.quartzplay.service.JobManagementService.JobAlreadyRunningException;
import com.willy.quartzplay.service.JobManagementService.JobDeleteException;
import com.willy.quartzplay.service.JobManagementService.JobInterruptException;
import com.willy.quartzplay.service.JobManagementService.JobListException;
import com.willy.quartzplay.service.JobManagementService.JobNotFoundException;
import com.willy.quartzplay.service.JobManagementService.JobNotInterruptableException;
import com.willy.quartzplay.service.JobManagementService.JobNotRunningException;
import com.willy.quartzplay.service.JobManagementService.JobPauseException;
import com.willy.quartzplay.service.JobManagementService.JobPausedException;
import com.willy.quartzplay.service.JobManagementService.JobResumeException;
import com.willy.quartzplay.service.JobManagementService.JobTriggerException;
import com.willy.quartzplay.service.JobManagementService.MultipleTriggersException;
import com.willy.quartzplay.service.JobManagementService.NoCronTriggersException;
import com.willy.quartzplay.service.JobManagementService.RescheduleException;
import com.willy.quartzplay.service.JobManagementService.SkipNextException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = JobController.class)
public class JobControllerAdvice {

    @ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public JobResponse handleNotFound(JobNotFoundException e) {
        return new JobResponse("error", e.getMessage());
    }

    @ExceptionHandler({InvalidCronExpressionException.class, InvalidTimezoneException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public JobResponse handleBadRequest(RuntimeException e) {
        return new JobResponse("error", e.getMessage());
    }

    @ExceptionHandler({JobAlreadyRunningException.class, JobNotRunningException.class,
                        JobNotInterruptableException.class, JobPausedException.class,
                        NoCronTriggersException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public JobResponse handleConflict(RuntimeException e) {
        return new JobResponse("error", e.getMessage());
    }

    @ExceptionHandler({JobListException.class, JobTriggerException.class, JobPauseException.class,
                        JobResumeException.class, JobInterruptException.class, SkipNextException.class,
                        RescheduleException.class, JobDeleteException.class, MultipleTriggersException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public JobResponse handleInternalError(RuntimeException e) {
        return new JobResponse("error", e.getMessage());
    }
}
