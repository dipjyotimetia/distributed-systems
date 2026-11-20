package com.banking.transactionsservice.service;

import com.banking.transactionsservice.client.AccountsServiceClient;
import com.banking.transactionsservice.entity.Transaction;
import com.banking.transactionsservice.event.TransactionEventOutbox;
import com.banking.transactionsservice.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class MovementCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(MovementCoordinator.class);
    public record Movement(long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {}
    public enum Action { RESERVE, COMMIT, COMPENSATE }
    private record Decision(UUID operation, Movement movement, String state, boolean finalized) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate durable;
    private final AccountsServiceClient accounts;
    private final TransactionRepository ledger;
    private final TransactionEventOutbox outbox;

    public MovementCoordinator(JdbcTemplate jdbc, PlatformTransactionManager manager, AccountsServiceClient accounts,
                               TransactionRepository ledger, TransactionEventOutbox outbox) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
        durable = new TransactionTemplate(manager);
        durable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Transaction execute(Transaction transaction) {
        if (java.util.Objects.equals(transaction.getFromAccountId(), transaction.getToAccountId())) {
            throw new IllegalArgumentException("Movement requires distinct accounts");
        }
        BigDecimal amount = BigDecimal.valueOf(transaction.getAmount()).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0 || amount.precision() > 19) {
            throw new IllegalArgumentException("Invalid movement amount");
        }
        Movement movement = new Movement(transaction.getUserId(), transaction.getFromAccountId(), transaction.getToAccountId(), amount);
        reconcile(movement);
        UUID operation = UUID.randomUUID();
        durable.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO transactions_service.movement_decisions
                    (operation_id, user_id, from_account_id, to_account_id, amount, state)
                VALUES (?, ?, ?, ?, ?, 'STARTED')
                """, operation, movement.userId(), movement.fromAccountId(), movement.toAccountId(), amount));
        Transaction saved;
        try {
            saved = durable.execute(status -> {
                Decision decision = lock(operation);
                if (!"STARTED".equals(decision.state())) {
                    throw new IllegalStateException("Movement already resolved");
                }
                accounts.applyMovement(operation, Action.RESERVE, movement);
                transaction.setStatus(Transaction.TransactionStatus.COMPLETED);
                transaction.setProcessedAt(LocalDateTime.now());
                Transaction entry = ledger.saveAndFlush(transaction);
                outbox.enqueue(entry);
                jdbc.update("""
                        UPDATE transactions_service.movement_decisions SET state = 'COMMITTED', ledger_id = ?
                        WHERE operation_id = ?
                        """, entry.getId(), operation);
                return entry;
            });
        } catch (RuntimeException failure) {
            String outcome;
            try {
                // Read the decision under a fresh database lock. If the ledger is
                // unavailable, leave any account hold in place for reconciliation.
                outcome = resolve(operation);
            } catch (RuntimeException unresolved) {
                failure.addSuppressed(unresolved);
                throw pending(failure);
            }
            if ("COMMITTED".equals(outcome)) {
                throw pending(failure);
            }
            throw failure;
        }

        try {
            resolve(operation);
        } catch (RuntimeException failure) {
            throw pending(failure);
        }
        return saved;
    }

    private static ResponseStatusException pending(RuntimeException cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Movement outcome requires reconciliation", cause);
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void reconcilePendingMovements() {
        var unresolved = jdbc.queryForList("""
                SELECT operation_id FROM transactions_service.movement_decisions
                WHERE NOT finalized AND (state <> 'STARTED' OR created_at < ?)
                ORDER BY created_at, operation_id LIMIT 50
                """, UUID.class, LocalDateTime.now().minusSeconds(30));
        for (UUID operation : unresolved) {
            try {
                resolve(operation);
            } catch (RuntimeException failure) {
                logger.warn("Movement {} remains unresolved ({})", operation, failure.getClass().getSimpleName());
            }
        }
    }

    private void reconcile(Movement movement) {
        var unresolved = jdbc.queryForList("""
                SELECT operation_id FROM transactions_service.movement_decisions
                WHERE user_id = ? AND NOT finalized
                  AND (from_account_id IN (?, ?) OR to_account_id IN (?, ?))
                  AND (state <> 'STARTED' OR created_at < ?)
                ORDER BY created_at, operation_id
                """, UUID.class, movement.userId(), movement.fromAccountId(), movement.toAccountId(),
                movement.fromAccountId(), movement.toAccountId(), LocalDateTime.now().minusSeconds(30));
        unresolved.forEach(this::resolve);
    }

    private String resolve(UUID operation) {
        Decision decision = durable.execute(status -> {
            Decision current = lock(operation);
            if ("STARTED".equals(current.state())) {
                jdbc.update("UPDATE transactions_service.movement_decisions SET state = 'ABORTED' WHERE operation_id = ?", operation);
                return new Decision(operation, current.movement(), "ABORTED", false);
            }
            return current;
        });
        if (!decision.finalized()) {
            accounts.applyMovement(operation, "COMMITTED".equals(decision.state()) ? Action.COMMIT : Action.COMPENSATE,
                    decision.movement());
            durable.executeWithoutResult(status -> jdbc.update(
                    "UPDATE transactions_service.movement_decisions SET finalized = TRUE WHERE operation_id = ?", operation));
        }
        return decision.state();
    }

    private Decision lock(UUID operation) {
        return jdbc.queryForObject("""
                SELECT * FROM transactions_service.movement_decisions WHERE operation_id = ? FOR UPDATE
                """, (rs, row) -> new Decision(operation, new Movement(rs.getLong("user_id"),
                rs.getObject("from_account_id", Long.class), rs.getObject("to_account_id", Long.class),
                rs.getBigDecimal("amount")), rs.getString("state"), rs.getBoolean("finalized")), operation);
    }
}
