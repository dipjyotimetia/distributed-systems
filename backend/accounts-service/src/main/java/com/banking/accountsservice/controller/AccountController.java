package com.banking.accountsservice.controller;

import com.banking.accountsservice.contract.AccountsApi;
import com.banking.accountsservice.contract.ContractJson;
import com.banking.accountsservice.contract.model.AccountCreateRequest;
import com.banking.accountsservice.contract.model.AccountResponse;
import com.banking.accountsservice.contract.model.AccountStatus;
import com.banking.accountsservice.contract.model.AccountSummary;
import com.banking.accountsservice.contract.model.AccountUpdateRequest;
import com.banking.accountsservice.contract.model.BalanceResponse;
import com.banking.accountsservice.contract.model.BalanceUpdateRequest;
import com.banking.accountsservice.contract.model.StatementExport;
import com.banking.accountsservice.contract.model.StatusUpdateRequest;
import com.banking.accountsservice.dto.AccountQuery;
import com.banking.accountsservice.entity.Account.Status;
import com.banking.accountsservice.security.UserAuthenticationDetails;
import com.banking.accountsservice.service.AccountService;
import com.banking.accountsservice.service.FinancialDataService;
import com.banking.accountsservice.service.StatementExportService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@RestController
public class AccountController implements AccountsApi {

    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);

    @Autowired
    private AccountService accountService;

    @Autowired
    private FinancialDataService financialDataService;

    @Autowired
    private StatementExportService statementExportService;

    @Autowired
    private Tracer tracer;

    @Autowired
    private ContractJson contractJson;

    @Autowired
    private Validator validator;

    @Override
    public ResponseEntity<Object> listAccounts(@Nullable String type, @Nullable AccountStatus status,
                                               @Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
        AccountQuery query = query(type, status, null, page, size, sort);
        if (!query.isUnfiltered()) {
            return ResponseEntity.ok(accountService.queryAccounts(getUserIdFromAuthentication(), query, false));
        }
        Span span = tracer.spanBuilder("get-user-accounts").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();
            logger.info("Fetching accounts for user: {}", userId);
            var accounts = accountService.getUserAccounts(userId);
            span.setAttribute("user.id", userId);
            span.setAttribute("accounts.count", accounts.size());
            return ResponseEntity.ok(accounts);
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error fetching accounts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @Override
    public ResponseEntity<Object> searchAccounts(@Nullable String q, @Nullable String type, @Nullable AccountStatus status,
                                                 @Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
        return ResponseEntity.ok(accountService.queryAccounts(getUserIdFromAuthentication(),
                query(type, status, q, page, size, sort), true));
    }

    @Override
    public ResponseEntity<AccountSummary> summarizeAccounts() {
        return ResponseEntity.ok(contractJson.convert(accountService.summarize(getUserIdFromAuthentication()), AccountSummary.class));
    }

    @Override
    public ResponseEntity<AccountResponse> createAccount(AccountCreateRequest accountCreateRequest) {
        Span span = tracer.spanBuilder("create-account").startSpan();
        try {
            var request = contractJson.convert(accountCreateRequest, com.banking.accountsservice.dto.AccountCreateRequest.class);
            if (!validator.validate(request).isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            Long userId = getUserIdFromAuthentication();
            logger.info("Creating account for user: {}", userId);
            var account = accountService.createAccount(request, userId);
            span.setAttribute("user.id", userId);
            span.setAttribute("account.type", request.getAccountType());
            span.setAttribute("account.number", account.getAccountNumber());
            return ResponseEntity.status(HttpStatus.CREATED).body(contractJson.convert(account, AccountResponse.class));
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error creating account", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @Override
    public ResponseEntity<AccountResponse> getAccount(Long accountId) {
        Span span = tracer.spanBuilder("get-account-by-id").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();
            logger.info("Fetching account {} for user: {}", accountId, userId);
            var account = accountService.getAccountById(accountId, userId);
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", accountId);
            span.setAttribute("account.number", account.getAccountNumber());
            return ResponseEntity.ok(contractJson.convert(account, AccountResponse.class));
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Account not found or access denied", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error fetching account", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatusException(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(failure.getBody());
    }

    @Override
    public ResponseEntity<AccountResponse> updateAccount(Long accountId, AccountUpdateRequest accountUpdateRequest) {
        Status status = accountUpdateRequest.getStatus() == null ? null : Status.valueOf(accountUpdateRequest.getStatus().getValue());
        String accountType = accountUpdateRequest.getAccountType() == null ? null : accountUpdateRequest.getAccountType().getValue();
        var account = accountService.updateAccount(accountId, getUserIdFromAuthentication(), accountType, status);
        return ResponseEntity.ok(contractJson.convert(account, AccountResponse.class));
    }

    @Override
    public ResponseEntity<AccountResponse> updateAccountStatus(Long accountId, StatusUpdateRequest statusUpdateRequest) {
        if (statusUpdateRequest == null || statusUpdateRequest.getStatus() == null) {
            return ResponseEntity.badRequest().build();
        }
        var account = accountService.updateAccount(accountId, getUserIdFromAuthentication(), null,
                Status.valueOf(statusUpdateRequest.getStatus().getValue()));
        return ResponseEntity.ok(contractJson.convert(account, AccountResponse.class));
    }

    @Override
    public ResponseEntity<Void> closeAccount(Long accountId) {
        accountService.updateAccount(accountId, getUserIdFromAuthentication(), null, Status.CLOSED);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<BalanceResponse> getAccountBalance(Long accountId) {
        Span span = tracer.spanBuilder("get-account-balance").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();
            logger.info("Fetching balance for account {} for user: {}", accountId, userId);
            var balance = accountService.getAccountBalance(accountId, userId);
            financialDataService.enrichAsync(balance.getAccountNumber());
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", accountId);
            span.setAttribute("account.number", balance.getAccountNumber());
            return ResponseEntity.ok(contractJson.convert(balance, BalanceResponse.class));
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Account not found or access denied", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error fetching account balance", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @Override
    public ResponseEntity<Void> updateAccountBalance(Long accountId, BalanceUpdateRequest balanceUpdateRequest) {
        Span span = tracer.spanBuilder("update-account-balance").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();
            logger.info("Updating balance for account {} for user: {}", accountId, userId);
            BigDecimal newBalance = balanceUpdateRequest.getBalance();
            if (newBalance == null) {
                return ResponseEntity.badRequest().build();
            }
            accountService.updateBalance(accountId, newBalance, userId);
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", accountId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Account not found or access denied", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error updating account balance", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @Override
    public ResponseEntity<StatementExport> exportStatement(Long accountId) {
        Span span = tracer.spanBuilder("export-statement").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", accountId);
            Map<String, Object> body = new HashMap<>();
            if (!statementExportService.isConfigured()) {
                body.put("status", "generated");
                body.put("statement", statementExportService.generateStatement(accountId, userId));
                return ResponseEntity.ok(contractJson.convert(body, StatementExport.class));
            }
            logger.info("Exporting statement for account {} user {}", accountId, userId);
            String key = statementExportService.exportStatement(accountId, userId);
            span.setAttribute("s3.key", key);
            body.put("status", "exported");
            body.put("key", key);
            return ResponseEntity.ok(contractJson.convert(body, StatementExport.class));
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Statement export failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    private static AccountQuery query(String type, AccountStatus status, String q, Integer page, Integer size, String sort) {
        Status parsed = status == null ? null : Status.valueOf(status.getValue());
        return new AccountQuery(type, parsed, q, page, size, sort);
    }

    private Long getUserIdFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getDetails() == null) {
            throw new RuntimeException("User not authenticated");
        }
        UserAuthenticationDetails details = (UserAuthenticationDetails) authentication.getDetails();
        return details.getUserId();
    }
}
