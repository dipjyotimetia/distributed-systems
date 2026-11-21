package com.banking.transactionsservice.service;

import com.banking.transactionsservice.client.AccountsServiceClient;
import com.banking.transactionsservice.client.FraudServiceClient;
import com.banking.transactionsservice.dto.*;
import com.banking.transactionsservice.entity.Transaction;
import com.banking.transactionsservice.entity.Transaction.TransactionStatus;
import com.banking.transactionsservice.entity.Transaction.TransactionType;
import com.banking.transactionsservice.repository.TransactionRepository;
import io.opentelemetry.api.metrics.DoubleHistogram;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@Transactional
public class TransactionService {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);

    // ISO 18245 merchant category codes used to tag outbound txns for the
    // fraud engine. Distribution mirrors the live merchant mix observed in
    // prod billing data: grocery is the long tail, dining/pharma/electronics
    // are episodic, gambling is rare. Withdrawals/deposits at the bank's own
    // ATM/branch are tagged 6011 (financial institutions).
    private static final String[] MCC_POOL = {
            "5411", "5411", "5411", "5411",   // grocery stores
            "5812", "5812", "5812",            // restaurants
            "5912", "5912",                    // drug stores
            "5732",                            // consumer electronics
            "7995",                            // gambling
            "5999", "5999", "5999",            // misc retail
            "5541",                            // service stations
    };

    @Value("${fraud.transfer-merchant-category:}")
    private String transferMerchantCategory;

    private String pickMerchantCategory(String transactionType) {
        if ("DEPOSIT".equals(transactionType) || "WITHDRAWAL".equals(transactionType)) {
            return "6011"; // financial institutions
        }
        if (transferMerchantCategory != null && !transferMerchantCategory.isBlank()) {
            return transferMerchantCategory;
        }
        return MCC_POOL[ThreadLocalRandom.current().nextInt(MCC_POOL.length)];
    }
    
    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MovementCoordinator movementCoordinator;
    
    @Autowired
    private AccountsServiceClient accountsServiceClient;

    @Autowired
    private FraudServiceClient fraudServiceClient;

    @Autowired
    private PaymentComplianceService paymentComplianceService;

    @Autowired
    private DoubleHistogram depositAmountHistogram;

    @Autowired
    private DoubleHistogram withdrawAmountHistogram;

    @Autowired
    private DoubleHistogram transferAmountHistogram;

    
    private static final Set<String> SORTABLE_FIELDS = Set.of("id", "createdAt", "processedAt", "amount", "type", "status");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    @Transactional(readOnly = true)
    public Object queryTransactions(Long userId, TransactionQuery query, boolean search, HttpServletRequest request) {
        requireAccountOwnership(query.accountId(), request);
        if (!search && query.isUnfiltered()) {
            return getUserTransactions(userId);
        }
        LocalDateTime start = parseDate(query.startDate(), false);
        LocalDateTime end = parseDate(query.endDate(), true);
        Specification<Transaction> filters = (root, criteria, builder) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(builder.equal(root.get("userId"), userId));
            if (query.accountId() != null) {
                predicates.add(builder.or(builder.equal(root.get("fromAccountId"), query.accountId()),
                        builder.equal(root.get("toAccountId"), query.accountId())));
            }
            if (query.type() != null) predicates.add(builder.equal(root.get("type"), query.type()));
            if (query.status() != null) predicates.add(builder.equal(root.get("status"), query.status()));
            if (start != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), start));
            if (end != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), end));
            if (query.minAmount() != null) predicates.add(builder.ge(root.get("amount"), query.minAmount()));
            if (query.maxAmount() != null) predicates.add(builder.le(root.get("amount"), query.maxAmount()));
            for (String text : new String[]{query.description(), query.q()}) {
                if (text != null && !text.isBlank()) {
                    String escaped = text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
                    predicates.add(builder.like(builder.lower(root.get("description")), "%" + escaped + "%", '!'));
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Sort sort = transactionSort(query.sort());
        if (!search && query.page() == null && query.size() == null) {
            return transactionRepository.findAll(filters, sort).stream().map(this::convertToResponse).toList();
        }
        int page = query.page() == null ? 0 : query.page();
        int size = query.size() == null ? 10 : query.size();
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        return transactionRepository.findAll(filters, PageRequest.of(page, size, sort)).map(this::convertToResponse);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> recentTransactions(Long userId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid limit");
        }
        Specification<Transaction> owned = (root, criteria, builder) -> builder.equal(root.get("userId"), userId);
        return transactionRepository.findAll(owned, PageRequest.of(0, limit, NEWEST_FIRST))
                .map(this::convertToResponse).getContent();
    }

    @Transactional(readOnly = true)
    public TransactionSummary summarize(Long userId, Long accountId, HttpServletRequest request) {
        requireAccountOwnership(accountId, request);
        var byType = new EnumMap<TransactionType, Long>(TransactionType.class);
        var byStatus = new EnumMap<TransactionStatus, Long>(TransactionStatus.class);
        for (var type : TransactionType.values()) byType.put(type, 0L);
        for (var status : TransactionStatus.values()) byStatus.put(status, 0L);
        long total = 0;
        for (var group : transactionRepository.summarize(userId, accountId)) {
            byType.merge(group.getType(), group.getTotal(), Long::sum);
            byStatus.merge(group.getStatus(), group.getTotal(), Long::sum);
            total += group.getTotal();
        }
        return new TransactionSummary(total, byType.get(TransactionType.DEPOSIT),
                byType.get(TransactionType.WITHDRAWAL), byType.get(TransactionType.TRANSFER),
                byStatus.get(TransactionStatus.PENDING), byStatus.get(TransactionStatus.COMPLETED),
                byStatus.get(TransactionStatus.FAILED), byType, byStatus);
    }

    private void requireAccountOwnership(Long accountId, HttpServletRequest request) {
        if (accountId != null && !accountsServiceClient.validateAccountOwnership(accountId, request)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
    }

    private static Sort transactionSort(String value) {
        if (value == null || value.isBlank()) return NEWEST_FIRST;
        String[] parts = value.split(",", -1);
        if (parts.length != 2 || !SORTABLE_FIELDS.contains(parts[0])) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction sort");
        }
        Sort.Direction direction = Sort.Direction.fromOptionalString(parts[1])
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sort direction"));
        Sort primary = Sort.by(direction, parts[0]);
        return parts[0].equals("id") ? primary : primary.and(Sort.by(direction, "id"));
    }

    private static LocalDateTime parseDate(String value, boolean end) {
        if (value == null) return null;
        try {
            if (value.length() != 10) return LocalDateTime.parse(value);
            LocalDate date = LocalDate.parse(value);
            return end ? date.atTime(java.time.LocalTime.MAX) : date.atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction date");
        }
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(Long id, Long userId) {
        return convertToResponse(requireOwnedTransaction(id, userId));
    }

    public TransactionResponse cancelTransaction(Long id, Long userId) {
        transactionRepository.cancelPending(id, userId, Transaction.TransactionStatus.PENDING,
                Transaction.TransactionStatus.CANCELLED, LocalDateTime.now());
        Transaction transaction = requireOwnedTransaction(id, userId);
        if (transaction.getStatus() != Transaction.TransactionStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending transactions can be cancelled");
        }
        return convertToResponse(transaction);
    }

    private Transaction requireOwnedTransaction(Long id, Long userId) {
        return transactionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
    }

    public List<TransactionResponse> getUserTransactions(Long userId) {
        logger.info("Fetching transactions for user: {}", userId);
        List<Transaction> transactions = transactionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }
    
    public List<TransactionResponse> getUserTransactionsByAccount(Long userId, Long accountId) {
        logger.info("Fetching transactions for user: {} and account: {}", userId, accountId);
        List<Transaction> transactions = transactionRepository.findByUserIdAndAccountIdOrderByCreatedAtDesc(userId, accountId);
        return transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }
    
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransactionResponse deposit(DepositRequest request, Long userId, HttpServletRequest httpRequest) {
        logger.info("Processing deposit for user: {}, account: {}, amount: {}",
                   userId, request.getAccountId(), request.getAmount());

        Transaction draft = new Transaction(userId, null, request.getAccountId(),
                request.getAmount(), Transaction.TransactionType.DEPOSIT, request.getDescription());
        var existing = recoverLegacyIntent(draft, httpRequest);
        if (existing != null) return existing;

        // Fraud check before processing
        var fraudResult = fraudServiceClient.checkTransaction(
                String.valueOf(request.getAccountId()), String.valueOf(userId),
                request.getAmount(), "DEPOSIT", pickMerchantCategory("DEPOSIT"));
        if (!fraudResult.getApproved()) {
            throw new RuntimeException("Transaction rejected by fraud check: " + fraudResult.getReason());
        }

        return complete(draft, httpRequest,
                () -> depositAmountHistogram.record(request.getAmount().doubleValue()),
                saved -> paymentComplianceService.fanOutPaymentAndCompliance(saved.getId(), "DEPOSIT", request.getAmount()));
    }
    
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransactionResponse withdraw(WithdrawRequest request, Long userId, HttpServletRequest httpRequest) {
        logger.info("Processing withdrawal for user: {}, account: {}, amount: {}",
                   userId, request.getAccountId(), request.getAmount());

        Transaction draft = new Transaction(userId, request.getAccountId(), null,
                request.getAmount(), Transaction.TransactionType.WITHDRAWAL, request.getDescription());
        var existing = recoverLegacyIntent(draft, httpRequest);
        if (existing != null) return existing;

        // Fraud check before processing
        var fraudResult = fraudServiceClient.checkTransaction(
                String.valueOf(request.getAccountId()), String.valueOf(userId),
                request.getAmount(), "WITHDRAWAL", pickMerchantCategory("WITHDRAWAL"));
        if (!fraudResult.getApproved()) {
            throw new RuntimeException("Transaction rejected by fraud check: " + fraudResult.getReason());
        }

        return complete(draft, httpRequest,
                () -> withdrawAmountHistogram.record(request.getAmount().doubleValue()),
                saved -> paymentComplianceService.fanOutPaymentAndCompliance(saved.getId(), "WITHDRAWAL", request.getAmount()));
    }
    
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransactionResponse transfer(TransferRequest request, Long userId, HttpServletRequest httpRequest) {
        logger.info("Processing transfer for user: {}, from: {}, to: {}, amount: {}",
                   userId, request.getFromAccountId(), request.getToAccountId(), request.getAmount());

        Transaction draft = new Transaction(userId, request.getFromAccountId(), request.getToAccountId(),
                request.getAmount(), Transaction.TransactionType.TRANSFER, request.getDescription());
        var existing = recoverLegacyIntent(draft, httpRequest);
        if (existing != null) return existing;

        // Fraud check before processing
        var fraudResult = fraudServiceClient.checkTransaction(
                String.valueOf(request.getFromAccountId()), String.valueOf(userId),
                request.getAmount(), "TRANSFER", pickMerchantCategory("TRANSFER"));
        if (!fraudResult.getApproved()) {
            throw new RuntimeException("Transaction rejected by fraud check: " + fraudResult.getReason());
        }

        paymentComplianceService.verifyTransferCompliance(request.getFromAccountId(), request.getToAccountId());
        return complete(draft, httpRequest,
                () -> transferAmountHistogram.record(request.getAmount().doubleValue()),
                saved -> paymentComplianceService.fanOutPaymentAndCompliance(saved.getId(), "TRANSFER", request.getAmount()));
    }

    private TransactionResponse recoverLegacyIntent(Transaction draft, HttpServletRequest request) {
        String key = Idempotency.key(request);
        if (key == null) return null;
        return transactionRepository.findByUserIdAndIdempotencyKey(draft.getUserId(), key)
                .map(existing -> replay(existing, Idempotency.fingerprint(draft))).orElse(null);
    }

    private TransactionResponse complete(Transaction draft, HttpServletRequest httpRequest, Runnable metrics,
                                         java.util.function.Consumer<Transaction> afterCommit) {
        String key = Idempotency.key(httpRequest);
        String hash = null;
        if (key != null) {
            hash = Idempotency.fingerprint(draft);
            draft.setIdempotencyKey(key);
            draft.setIdempotencyHash(hash);
            var existing = transactionRepository.findByUserIdAndIdempotencyKey(draft.getUserId(), key);
            if (existing.isPresent()) {
                return replay(existing.get(), hash);
            }
        }
        try {
            Transaction saved = movementCoordinator.execute(draft);
            metrics.run();
            afterCommit.accept(saved);
            return convertToResponse(saved);
        } catch (DataIntegrityViolationException conflict) {
            if (key == null) {
                throw conflict;
            }
            return replay(transactionRepository.findByUserIdAndIdempotencyKey(draft.getUserId(), key)
                    .orElseThrow(() -> conflict), hash);
        }
    }

    private TransactionResponse replay(Transaction existing, String hash) {
        if (hash == null || !hash.equals(existing.getIdempotencyHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was reused for a different transaction");
        }
        if (existing.getStatus() == Transaction.TransactionStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Movement outcome requires reconciliation");
        }
        return convertToResponse(existing);
    }
    
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransactionResponse createTransaction(CreateTransactionRequest request, Long userId, HttpServletRequest httpRequest) {
        logger.info("Creating transaction for user: {}, account: {}, type: {}, amount: {}", 
                   userId, request.getAccountId(), request.getType(), request.getAmount());
        
        switch (request.getType()) {
            case "DEPOSIT":
                DepositRequest depositRequest = new DepositRequest();
                depositRequest.setAccountId(request.getAccountId());
                depositRequest.setAmount(request.getAmount());
                depositRequest.setDescription(request.getDescription());
                return deposit(depositRequest, userId, httpRequest);
                
            case "WITHDRAWAL":
                WithdrawRequest withdrawRequest = new WithdrawRequest();
                withdrawRequest.setAccountId(request.getAccountId());
                withdrawRequest.setAmount(request.getAmount());
                withdrawRequest.setDescription(request.getDescription());
                return withdraw(withdrawRequest, userId, httpRequest);
                
            case "TRANSFER":
                if (request.getToAccountId() == null) {
                    throw new RuntimeException("To account ID is required for transfer transactions");
                }
                TransferRequest transferRequest = new TransferRequest();
                transferRequest.setFromAccountId(request.getAccountId());
                transferRequest.setToAccountId(request.getToAccountId());
                transferRequest.setAmount(request.getAmount());
                transferRequest.setDescription(request.getDescription());
                return transfer(transferRequest, userId, httpRequest);
                
            default:
                throw new RuntimeException("Invalid transaction type: " + request.getType());
        }
    }

    private TransactionResponse convertToResponse(Transaction transaction) {
        return new TransactionResponse(
            transaction.getId(),
            transaction.getUserId(),
            transaction.getFromAccountId(),
            transaction.getToAccountId(),
            transaction.getAmount().doubleValue(),
            transaction.getType(),
            transaction.getDescription(),
            transaction.getStatus(),
            transaction.getCreatedAt(),
            transaction.getProcessedAt()
        );
    }
}
