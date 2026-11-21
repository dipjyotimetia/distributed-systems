package com.banking.accountsservice.service;

import com.banking.accountsservice.dto.AccountCreateRequest;
import com.banking.accountsservice.dto.AccountResponse;
import com.banking.accountsservice.dto.AccountQuery;
import com.banking.accountsservice.dto.AccountSummary;
import com.banking.accountsservice.dto.BalanceResponse;
import com.banking.accountsservice.entity.Account;
import com.banking.accountsservice.repository.AccountRepository;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class AccountService {
    
    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);
    
    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LongCounter createdAccountsCounter;

    @Autowired
    private MovementReservations movementReservations;
    
    private final SecureRandom random = new SecureRandom();
    
    public List<AccountResponse> getUserAccounts(Long userId) {
        logger.info("Fetching accounts for user: {}", userId);
        List<Account> accounts = accountRepository.findByUserId(userId);
        return accounts.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }
    
    private static final List<String> ACCOUNT_TYPES = List.of("CHECKING", "SAVINGS", "CREDIT", "INVESTMENT");
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "id", "accountNumber", "accountType", "balance", "status", "createdAt", "updatedAt");

    @Transactional(readOnly = true)
    public Object queryAccounts(Long userId, AccountQuery query, boolean search) {
        if (query.type() != null && !ACCOUNT_TYPES.contains(query.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account type");
        }
        Specification<Account> filters = (root, criteria, builder) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(builder.equal(root.get("userId"), userId));
            if (query.type() != null) predicates.add(builder.equal(root.get("accountType"), query.type()));
            if (query.status() != null) predicates.add(builder.equal(root.get("status"), query.status()));
            if (query.q() != null && !query.q().isBlank()) {
                String escaped = query.q().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
                String pattern = "%" + escaped + "%";
                predicates.add(builder.or(builder.like(root.get("accountNumber"), pattern, '!'),
                        builder.like(builder.lower(root.get("accountType")), pattern, '!')));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Sort sort = accountSort(query.sort());
        if (!search && query.page() == null && query.size() == null) {
            return accountRepository.findAll(filters, sort).stream().map(this::convertToResponse).toList();
        }
        int page = query.page() == null ? 0 : query.page();
        int size = query.size() == null ? 10 : query.size();
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        return accountRepository.findAll(filters, PageRequest.of(page, size, sort)).map(this::convertToResponse);
    }

    @Transactional(readOnly = true)
    public AccountSummary summarize(Long userId) {
        var byType = new LinkedHashMap<String, Long>();
        ACCOUNT_TYPES.forEach(type -> byType.put(type, 0L));
        BigDecimal balance = BigDecimal.ZERO;
        long total = 0;
        long active = 0;
        for (var group : accountRepository.summarize(userId)) {
            byType.merge(group.getAccountType(), group.getTotal(), Long::sum);
            balance = balance.add(group.getBalance());
            total += group.getTotal();
            if (group.getStatus() == Account.Status.ACTIVE) active += group.getTotal();
        }
        return new AccountSummary(total, balance, active, byType);
    }

    private static Sort accountSort(String value) {
        if (value == null || value.isBlank()) return Sort.by(Sort.Direction.DESC, "createdAt", "id");
        String[] parts = value.split(",", -1);
        if (parts.length != 2 || !SORTABLE_FIELDS.contains(parts[0])) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account sort");
        }
        Sort.Direction direction = Sort.Direction.fromOptionalString(parts[1])
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sort direction"));
        Sort primary = Sort.by(direction, parts[0]);
        return parts[0].equals("id") ? primary : primary.and(Sort.by(direction, "id"));
    }

    public AccountResponse getAccountById(Long accountId, Long userId) {
        logger.info("Fetching account {} for user: {}", accountId, userId);
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new RuntimeException("Account not found or access denied"));
        return convertToResponse(account);
    }
    
    public BalanceResponse getAccountBalance(Long accountId, Long userId) {
        logger.info("Fetching balance for account {} for user: {}", accountId, userId);
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new RuntimeException("Account not found or access denied"));
        BalanceResponse response = new BalanceResponse(account.getId(), account.getAccountNumber(), account.getBalance());
        response.setCurrency(account.getCurrency());
        return response;
    }
    
    public AccountResponse createAccount(AccountCreateRequest request, Long userId) {
        logger.info("Creating new {} account for user: {} with initial balance: {}", 
                   request.getAccountType(), userId, request.getInitialBalance());
        
        String accountNumber = generateAccountNumber();
        
        Account account = new Account();
        account.setUserId(userId);
        account.setAccountNumber(accountNumber);
        BigDecimal opening = (request.getInitialBalance() != null ? request.getInitialBalance() : BigDecimal.ZERO)
                .setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (opening.signum() < 0 || opening.precision() > 19) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid opening balance");
        }
        account.setBalance(opening);
        account.setAccountType(request.getAccountType());
        if (request.getCurrency() != null) {
            account.setCurrency(request.getCurrency());
        }
        
        Account savedAccount = accountRepository.save(account);
        movementReservations.recordPosting(savedAccount, java.util.UUID.randomUUID(), account.getBalance(), "OPENING");
        logger.info("Created account {} for user: {} with balance: {}", 
                   savedAccount.getAccountNumber(), userId, savedAccount.getBalance());
        
        createdAccountsCounter.add(1);
        
        return convertToResponse(savedAccount);
    }
    
    private String generateAccountNumber() {
        String accountNumber;
        do {
            // Generate 12-digit account number
            long number = 100000000000L + (long) (random.nextDouble() * 900000000000L);
            accountNumber = String.valueOf(number);
        } while (accountRepository.existsByAccountNumber(accountNumber));
        
        return accountNumber;
    }
    
    public boolean validateAccountOwnership(Long accountId, Long userId) {
        logger.info("Validating ownership of account {} for user: {}", accountId, userId);
        return accountRepository.existsByIdAndUserId(accountId, userId);
    }
    
    public AccountResponse updateAccount(Long accountId, Long userId, String accountType, Account.Status status) {
        if ((accountType == null && status == null) || (accountType != null && !ACCOUNT_TYPES.contains(accountType))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account type");
        }
        Account account = accountRepository.findOwnedForUpdate(accountId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        if (accountType != null && account.getStatus() == Account.Status.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Closed accounts cannot be changed");
        }
        if (status != null && status != account.getStatus()) {
            movementReservations.assertUnreserved(accountId);
            if (account.getStatus() == Account.Status.CLOSED || status == Account.Status.INACTIVE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid account status transition");
            }
            if (status == Account.Status.CLOSED && account.getBalance().signum() != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Account balance must be zero");
            }
            account.setStatus(status);
        }
        if (accountType != null) account.setAccountType(accountType);
        return convertToResponse(accountRepository.saveAndFlush(account));
    }

    public boolean updateBalance(Long accountId, BigDecimal newBalance, Long userId) {
        logger.info("Updating balance for account {} to {} for user: {}", accountId, newBalance, userId);
        Account account = accountRepository.findOwnedForUpdate(accountId, userId)
                .orElseThrow(() -> new RuntimeException("Account not found or access denied"));
        movementReservations.assertUnreserved(accountId);
        if (account.getStatus() != Account.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
        }
        BigDecimal exact = newBalance.setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (exact.signum() < 0 || exact.precision() > 19) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid balance");
        }
        BigDecimal delta = exact.subtract(account.getBalance());
        account.setBalance(exact);
        movementReservations.recordPosting(account, java.util.UUID.randomUUID(), delta, "MANUAL_ADJUSTMENT");
        accountRepository.save(account);
        return true;
    }

    private AccountResponse convertToResponse(Account account) {
        AccountResponse response = new AccountResponse(
                account.getId(),
                account.getUserId(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getAccountType(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
        response.setCurrency(account.getCurrency());
        response.setStatus(account.getStatus());
        return response;
    }
}