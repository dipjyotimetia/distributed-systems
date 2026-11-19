package com.banking.accountsservice.statements.application;

import com.banking.accountsservice.repository.AccountRepository;
import com.banking.accountsservice.statements.persistence.StatementJobStore;
import com.banking.accountsservice.statements.persistence.StatementJobStore.Job;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatementJobs {
    private static final int PAGE_SIZE = 20;
    private final AccountRepository accounts;
    private final StatementJobStore jobs;

    public StatementJobs(AccountRepository accounts, StatementJobStore jobs) {
        this.accounts = accounts;
        this.jobs = jobs;
    }

    public record Page(List<Job> items, boolean hasMore) {}

    public Job create(long account, long customer, String key) {
        requireOwner(account, customer);
        if (key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        return jobs.create(account, customer, key.strip());
    }

    public Page list(long account, long customer, int page) {
        requireOwner(account, customer);
        if (page < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
        }
        List<Job> items = jobs.list(account, customer, (page - 1L) * PAGE_SIZE);
        return new Page(items.subList(0, Math.min(PAGE_SIZE, items.size())), items.size() > PAGE_SIZE);
    }

    public Job status(long account, long customer, UUID id) {
        requireOwner(account, customer);
        return jobs.find(account, customer, id).orElseThrow(StatementJobs::notFound);
    }

    public byte[] download(long account, long customer, UUID id) {
        Job job = status(account, customer, id);
        if ("EXPIRED".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Statement expired");
        }
        if (!"READY".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Statement is not ready");
        }
        return jobs.download(account, customer, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Statement expired"));
    }

    private void requireOwner(long account, long customer) {
        if (accounts.findByIdAndUserId(account, customer).isEmpty()) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
