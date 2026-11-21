package com.banking.transactionsservice.controller;

import com.banking.transactionsservice.dto.TransactionQuery;
import com.banking.transactionsservice.generated.CreateTransactionRequest;
import com.banking.transactionsservice.generated.DepositRequest;
import com.banking.transactionsservice.generated.InsufficientFunds;
import com.banking.transactionsservice.generated.TransactionResponse;
import com.banking.transactionsservice.generated.TransactionSummary;
import com.banking.transactionsservice.generated.TransferRequest;
import com.banking.transactionsservice.generated.WithdrawRequest;
import com.banking.transactionsservice.security.UserAuthenticationDetails;
import com.banking.transactionsservice.service.InsufficientFundsException;
import com.banking.transactionsservice.service.TransactionService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);
    
    @Autowired
    private TransactionService transactionService;
    
    @Autowired
    private Tracer tracer;

    @GetMapping
    public ResponseEntity<?> getUserTransactions(@ModelAttribute TransactionQuery query) {
        if (query.isUnfiltered()) {
            return ResponseEntity.ok(transactionService.getUserTransactions(getUserIdFromAuthentication()));
        }
        return ResponseEntity.ok(transactionService.queryTransactions(
                getUserIdFromAuthentication(), query, false, getCurrentRequest()));
    }

    @GetMapping("/search")
    public Object search(@ModelAttribute TransactionQuery query) {
        return transactionService.queryTransactions(getUserIdFromAuthentication(), query, true, getCurrentRequest());
    }

    @GetMapping("/recent")
    public List<TransactionResponse> recent(@RequestParam(defaultValue = "10") int limit) {
        return transactionService.recentTransactions(getUserIdFromAuthentication(), limit);
    }

    @GetMapping("/summary")
    public TransactionSummary summary(@RequestParam(required = false) Long accountId) {
        return transactionService.summarize(getUserIdFromAuthentication(), accountId, getCurrentRequest());
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransaction(@PathVariable Long id) {
        return transactionService.getTransaction(id, getUserIdFromAuthentication());
    }

    @PatchMapping("/{id}/cancel")
    public TransactionResponse cancelTransaction(@PathVariable Long id) {
        return transactionService.cancelTransaction(id, getUserIdFromAuthentication());
    }

    @PostMapping("/create")
    public ResponseEntity<?> createTransaction(@Valid @RequestBody CreateTransactionRequest request) {
        Span span = tracer.spanBuilder("create-transaction").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();
            
            logger.info("Creating transaction for user: {}, account: {}, type: {}, amount: {}", 
                       userId, request.getAccountId(), request.getType(), request.getAmount());
            
            TransactionResponse transaction = transactionService.createTransaction(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", request.getAccountId());
            span.setAttribute("transaction.type", request.getType().getValue());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
        } catch (InsufficientFundsException e) {
            return ResponseEntity.badRequest().body(new InsufficientFunds(
                    InsufficientFunds.CodeEnum.INSUFFICIENT_FUNDS, e.getMessage()));
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
    
    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(
            @Valid @RequestBody DepositRequest request) {
        Span span = tracer.spanBuilder("process-deposit").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();

            logger.info("Processing deposit for user: {}, account: {}, amount: {}",
                       userId, request.getAccountId(), request.getAmount());

            TransactionResponse transaction = transactionService.deposit(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", request.getAccountId());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());

            return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
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
    
    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(
            @Valid @RequestBody WithdrawRequest request) {
        Span span = tracer.spanBuilder("process-withdrawal").startSpan();
        try {
            Long userId = getUserIdFromAuthentication();
            
            logger.info("Processing withdrawal for user: {}, account: {}, amount: {}", 
                       userId, request.getAccountId(), request.getAmount());
            
            TransactionResponse transaction = transactionService.withdraw(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("account.id", request.getAccountId());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
        } catch (InsufficientFundsException e) {
            return ResponseEntity.badRequest().body(new InsufficientFunds(
                    InsufficientFunds.CodeEnum.INSUFFICIENT_FUNDS, e.getMessage()));
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
    
    @PostMapping("/transfer")
    public ResponseEntity<?> transfer(
            @Valid @RequestBody TransferRequest request) {
        Span span = tracer.spanBuilder("process-transfer").startSpan();
        try {
            // Validate same account transfer
            if (request.getFromAccountId().equals(request.getToAccountId())) {
                logger.warn("Transfer rejected: from and to accounts are the same");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            
            Long userId = getUserIdFromAuthentication();
            
            logger.info("Processing transfer for user: {}, from: {}, to: {}, amount: {}", 
                       userId, request.getFromAccountId(), request.getToAccountId(), request.getAmount());
            
            TransactionResponse transaction = transactionService.transfer(request, userId, getCurrentRequest());
            span.setAttribute("user.id", userId);
            span.setAttribute("from.account.id", request.getFromAccountId());
            span.setAttribute("to.account.id", request.getToAccountId());
            span.setAttribute("transaction.amount", request.getAmount().toString());
            span.setAttribute("transaction.id", transaction.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
        } catch (InsufficientFundsException e) {
            return ResponseEntity.badRequest().body(new InsufficientFunds(
                    InsufficientFunds.CodeEnum.INSUFFICIENT_FUNDS, e.getMessage()));
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
