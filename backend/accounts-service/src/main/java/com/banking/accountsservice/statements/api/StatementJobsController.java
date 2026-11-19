package com.banking.accountsservice.statements.api;

import com.banking.accountsservice.security.UserAuthenticationDetails;
import com.banking.accountsservice.statements.application.StatementJobs;
import com.banking.accountsservice.statements.persistence.StatementJobStore.Job;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/accounts/{accountId}/statement-jobs")
public class StatementJobsController {
    private final StatementJobs jobs;

    public StatementJobsController(StatementJobs jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<Job> create(@PathVariable long accountId,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        Job job = jobs.create(accountId, customer(authentication), key);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/accounts/" + accountId + "/statement-jobs/" + job.id())).body(job);
    }

    @GetMapping
    public ResponseEntity<StatementJobs.Page> list(@PathVariable long accountId,
            @RequestParam(defaultValue = "1") int page, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(jobs.list(accountId, customer(authentication), page));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Job> status(@PathVariable long accountId, @PathVariable UUID jobId,
            Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(jobs.status(accountId, customer(authentication), jobId));
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<byte[]> download(@PathVariable long accountId, @PathVariable UUID jobId,
            Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"statement-" + jobId + ".json\"")
                .body(jobs.download(accountId, customer(authentication), jobId));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> commandError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).body(error.getBody());
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> badRequest() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid statement job request"));
    }

    private static long customer(Authentication authentication) {
        return ((UserAuthenticationDetails) authentication.getDetails()).getUserId();
    }
}
