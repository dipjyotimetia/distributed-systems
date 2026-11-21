package com.banking.accountsservice.statements.api;

import com.banking.accountsservice.contract.ContractJson;
import com.banking.accountsservice.contract.StatementJobsApi;
import com.banking.accountsservice.contract.model.StatementJob;
import com.banking.accountsservice.contract.model.StatementJobPage;
import com.banking.accountsservice.security.UserAuthenticationDetails;
import com.banking.accountsservice.statements.application.StatementJobs;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class StatementJobsController implements StatementJobsApi {
    private final StatementJobs jobs;
    private final ContractJson contractJson;

    public StatementJobsController(StatementJobs jobs, ContractJson contractJson) {
        this.jobs = jobs;
        this.contractJson = contractJson;
    }

    @Override
    public ResponseEntity<StatementJob> createStatementJob(Long accountId, String idempotencyKey) {
        var job = jobs.create(accountId, customer(), idempotencyKey);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/accounts/" + accountId + "/statement-jobs/" + job.id()))
                .body(contractJson.convert(job, StatementJob.class));
    }

    @Override
    public ResponseEntity<StatementJobPage> listStatementJobs(Long accountId, Integer page) {
        int requested = page == null ? 1 : page;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(contractJson.convert(jobs.list(accountId, customer(), requested), StatementJobPage.class));
    }

    @Override
    public ResponseEntity<StatementJob> getStatementJob(Long accountId, UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(contractJson.convert(jobs.status(accountId, customer(), jobId), StatementJob.class));
    }

    @Override
    public ResponseEntity<org.springframework.core.io.Resource> downloadStatementJob(Long accountId, UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"statement-" + jobId + ".json\"")
                .body(new ByteArrayResource(jobs.download(accountId, customer(), jobId)));
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

    private static long customer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ((UserAuthenticationDetails) authentication.getDetails()).getUserId();
    }
}
