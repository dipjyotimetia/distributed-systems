package com.banking.transactionsservice.controller;

import com.banking.transactionsservice.contract.ContractJson;
import com.banking.transactionsservice.contract.TransactionsApi;
import com.banking.transactionsservice.contract.model.TransactionResponse;
import com.banking.transactionsservice.contract.model.TransactionSummary;
import com.banking.transactionsservice.dto.TransactionQuery;
import com.banking.transactionsservice.entity.Transaction.TransactionStatus;
import com.banking.transactionsservice.entity.Transaction.TransactionType;
import com.banking.transactionsservice.security.UserAuthenticationDetails;
import com.banking.transactionsservice.service.InsufficientFundsException;
import com.banking.transactionsservice.service.TransactionService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class TransactionController implements TransactionsApi {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private Tracer tracer;

    @Autowired
    private ContractJson contractJson;

    @Autowired
    private Validator validator;

    @Override
    public ResponseEntity<Object> listTransactions(@Nullable Long accountId, @Nullable String type, @Nullable String status,
                                                   @Nullable String startDate, @Nullable String endDate,
                                                   @Nullable BigDecimal minAmount, @Nullable BigDecimal maxAmount,
                                                   @Nullable String description, @Nullable Integer page,
                                                   @Nullable Integer size, @Nullable String sort) {
        TransactionQuery query = query(accountId, type, status, startDate, endDate, minAmount, maxAmount, description, null, page, size, sort);
        if (query.isUnfiltered()) {
            return ResponseEntity.ok(transactionService.getUserTransactions(getUserIdFromAuthentication()));
        }
        return ResponseEntity.ok(transactionService.queryTransactions(getUserIdFromAuthentication(), query, false, getCurrentRequest()));
    }

    @Override
    public ResponseEntity<Object> searchTransactions(@Nullable String q, @Nullable Long accountId, @Nullable String type,
                                                     @Nullable String status, @Nullable String startDate, @Nullable String endDate,
                                                     @Nullable BigDecimal minAmount, @Nullable BigDecimal maxAmount,
                                                     @Nullable String description, @Nullable Integer page,
                                                     @Nullable Integer size, @Nullable String sort) {
        TransactionQuery query = query(accountId, type, status, startDate, endDate, minAmount, maxAmount, description, q, page, size, sort);
        return ResponseEntity.ok(transactionService.queryTransactions(getUserIdFromAuthentication(), query, true, getCurrentRequest()));
    }

    @Override
    public ResponseEntity<List<TransactionResponse>> recentTransactions(Integer limit) {
        int requested = limit == null ? 10 : limit;
        List<TransactionResponse> body = transactionService.recentTransactions(getUserIdFromAuthentication(), requested).stream()
                .map(item -> contractJson.convert(item, TransactionResponse.class))
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<TransactionSummary> summarizeTransactions(@Nullable Long accountId) {
        var summary = transactionService.summarize(getUserIdFromAuthentication(), accountId, getCurrentRequest());
        return ResponseEntity.ok(contractJson.convert(summary, TransactionSummary.class));
    }

    @Override
    public ResponseEntity<TransactionResponse> getTransaction(Long id) {
        return ResponseEntity.ok(contractJson.convert(
                transactionService.getTransaction(id, getUserIdFromAuthentication()), TransactionResponse.class));
    }

    @Override
    public ResponseEntity<TransactionResponse> cancelTransaction(Long id) {
        return ResponseEntity.ok(contractJson.convert(
                transactionService.cancelTransaction(id, getUserIdFromAuthentication()), TransactionResponse.class));
    }

    @Override
    public ResponseEntity<TransactionResponse> createTransaction(
            com.banking.transactionsservice.contract.model.CreateTransactionRequest createTransactionRequest) {
        Span span = tracer.spanBuilder("create-transaction").startSpan();
        try {
            var request = contractJson.convert(createTransactionRequest, com.banking.transactionsservice.dto.CreateTransactionRequest.class);
            Map<String, String> errors = violations(request);
            if (!errors.isEmpty()) {
                logger.warn("Validation failed for transaction creation: {}", errors);
                span.setAttribute("validation.failed", true);
                return rejected(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors));
            }
            Long userId = getUserIdFromAuthentication();
            logger.info("Creating transaction for user: {}, account: {}, type: {}, amount: {}",
                    userId, request.getAccountId(), request.getType(), request.getAmount());
            var transaction = transactionService.createTransaction(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", request.getAccountId());
            span.setAttribute("transaction.type", request.getType());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(contractJson.convert(transaction, TransactionResponse.class));
        } catch (InsufficientFundsException e) {
            return rejected(ResponseEntity.badRequest().body(Map.of("code", "INSUFFICIENT_FUNDS", "message", e.getMessage())));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Transaction creation failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error creating transaction", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @Override
    public ResponseEntity<TransactionResponse> deposit(com.banking.transactionsservice.contract.model.DepositRequest depositRequest) {
        Span span = tracer.spanBuilder("process-deposit").startSpan();
        try {
            var request = contractJson.convert(depositRequest, com.banking.transactionsservice.dto.DepositRequest.class);
            if (!violations(request).isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            Long userId = getUserIdFromAuthentication();
            logger.info("Processing deposit for user: {}, account: {}, amount: {}", userId, request.getAccountId(), request.getAmount());
            var transaction = transactionService.deposit(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", request.getAccountId());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(contractJson.convert(transaction, TransactionResponse.class));
        } catch (NullPointerException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            logger.error("Deposit failed unexpectedly", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Deposit failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error processing deposit", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @Override
    public ResponseEntity<TransactionResponse> withdraw(com.banking.transactionsservice.contract.model.WithdrawRequest withdrawRequest) {
        Span span = tracer.spanBuilder("process-withdrawal").startSpan();
        try {
            var request = contractJson.convert(withdrawRequest, com.banking.transactionsservice.dto.WithdrawRequest.class);
            if (!violations(request).isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            Long userId = getUserIdFromAuthentication();
            logger.info("Processing withdrawal for user: {}, account: {}, amount: {}", userId, request.getAccountId(), request.getAmount());
            var transaction = transactionService.withdraw(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", request.getAccountId());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(contractJson.convert(transaction, TransactionResponse.class));
        } catch (InsufficientFundsException e) {
            return rejected(ResponseEntity.badRequest().body(Map.of("code", "INSUFFICIENT_FUNDS", "message", e.getMessage())));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Withdrawal failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error processing withdrawal", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    @Override
    public ResponseEntity<TransactionResponse> transfer(com.banking.transactionsservice.contract.model.TransferRequest transferRequest) {
        Span span = tracer.spanBuilder("process-transfer").startSpan();
        try {
            var request = contractJson.convert(transferRequest, com.banking.transactionsservice.dto.TransferRequest.class);
            if (!violations(request).isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            if (request.getFromAccountId().equals(request.getToAccountId())) {
                logger.warn("Transfer rejected: from and to accounts are the same");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            Long userId = getUserIdFromAuthentication();
            logger.info("Processing transfer for user: {}, from: {}, to: {}, amount: {}",
                    userId, request.getFromAccountId(), request.getToAccountId(), request.getAmount());
            var transaction = transactionService.transfer(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("from.account.id", request.getFromAccountId());
            span.setAttribute("to.account.id", request.getToAccountId());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(contractJson.convert(transaction, TransactionResponse.class));
        } catch (InsufficientFundsException e) {
            return rejected(ResponseEntity.badRequest().body(Map.of("code", "INSUFFICIENT_FUNDS", "message", e.getMessage())));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            span.recordException(e);
            logger.error("Transfer failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            span.recordException(e);
            logger.error("Error processing transfer", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            span.end();
        }
    }

    private TransactionQuery query(Long accountId, String type, String status, String startDate, String endDate,
                                   BigDecimal minAmount, BigDecimal maxAmount, String description, String q,
                                   Integer page, Integer size, String sort) {
        return new TransactionQuery(accountId, enumValue(TransactionType.class, type), enumValue(TransactionStatus.class, status),
                startDate, endDate, minAmount == null ? null : minAmount.doubleValue(),
                maxAmount == null ? null : maxAmount.doubleValue(), description, q, page, size, sort);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + type.getSimpleName());
        }
    }

    private Map<String, String> violations(Object request) {
        Set<ConstraintViolation<Object>> found = validator.validate(request);
        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<Object> violation : found) {
            errors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static <T> ResponseEntity<T> rejected(ResponseEntity<?> response) {
        return (ResponseEntity<T>) response;
    }

    private Long getUserIdFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getDetails() == null) {
            throw new RuntimeException("User not authenticated");
        }
        UserAuthenticationDetails details = (UserAuthenticationDetails) authentication.getDetails();
        return details.getUserId();
    }

    private HttpServletRequest getCurrentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }
}
