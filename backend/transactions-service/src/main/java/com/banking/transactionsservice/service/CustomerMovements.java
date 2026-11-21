package com.banking.transactionsservice.service;

import com.banking.transactionsservice.client.AccountsServiceClient;
import com.banking.transactionsservice.client.FraudServiceClient;
import com.banking.transactionsservice.contract.model.MovementIntent;
import com.banking.transactionsservice.contract.model.MovementOutcome;
import com.banking.transactionsservice.entity.Transaction;
import com.banking.transactionsservice.event.TransactionEventOutbox;
import com.banking.transactionsservice.service.MovementCoordinator.Action;
import com.banking.transactionsservice.service.MovementCoordinator.Movement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerMovements {
    private enum Stage { SCREENING, RESERVE, POST, POSTED, REJECTED }

    private record Intent(MovementIntent.TypeEnum type, Long from, Long to, String currency,
                          BigDecimal amount, String description) {}

    private record Entry(UUID id, long userId, Intent intent, Stage stage, String reason,
                         OffsetDateTime created, OffsetDateTime updated) {
        MovementOutcome outcome() {
            var status = switch (stage) {
                case POSTED -> MovementOutcome.StatusEnum.POSTED;
                case REJECTED -> MovementOutcome.StatusEnum.REJECTED;
                default -> MovementOutcome.StatusEnum.PENDING;
            };
            return new MovementOutcome(id, status, created, updated).reasonCode(reason);
        }
    }

    private static final RowMapper<Entry> ENTRY = (row, index) -> new Entry(
            row.getObject("id", UUID.class), row.getLong("user_id"),
            new Intent(MovementIntent.TypeEnum.valueOf(row.getString("type")),
                    row.getObject("from_account_id", Long.class), row.getObject("to_account_id", Long.class),
                    row.getString("currency"), row.getBigDecimal("amount"), row.getString("description")),
            Stage.valueOf(row.getString("stage")), row.getString("reason_code"),
            row.getObject("created_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class));

    @Value("${transactions.movements.worker-enabled:true}")
    private boolean workerEnabled;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AccountsServiceClient accounts;
    private final FraudServiceClient fraud;
    private final PaymentComplianceService compliance;
    private final TransactionEventOutbox outbox;
    // Jackson 2 for outbox JSON; Boot auto-configures tools.jackson (Jackson 3) as the ObjectMapper bean.
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Double> oldestPendingSeconds = new AtomicReference<>(0.0);
    private final Timer postingLatency;
    private final Counter screeningOutages;

    public CustomerMovements(JdbcTemplate jdbc, PlatformTransactionManager manager, AccountsServiceClient accounts,
                             FraudServiceClient fraud, PaymentComplianceService compliance,
                             TransactionEventOutbox outbox, MeterRegistry metrics) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        this.accounts = accounts;
        this.fraud = fraud;
        this.compliance = compliance;
        this.outbox = outbox;
        postingLatency = metrics.timer("transactions.movements.accepted.to.posted");
        screeningOutages = metrics.counter("transactions.movements.screening.outages");
        Gauge.builder("transactions.movements.oldest.pending.seconds", oldestPendingSeconds, AtomicReference::get)
                .register(metrics);
    }

    @Transactional
    public MovementOutcome submit(long userId, String key, MovementIntent body) {
        if (key == null || !key.matches("[!-~]{1,128}")) throw invalid();
        Intent intent = normalize(body);
        int inserted = jdbc.update("""
                INSERT INTO transactions_service.customer_movements
                    (id, user_id, idempotency_key, type, from_account_id, to_account_id, currency, amount, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), userId, key, intent.type().name(), intent.from(), intent.to(),
                intent.currency(), intent.amount(), intent.description());
        Entry entry = jdbc.queryForObject("""
                SELECT * FROM transactions_service.customer_movements WHERE user_id = ? AND idempotency_key = ?
                """, ENTRY, userId, key);
        if (!intent.equals(entry.intent())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        }
        if (inserted == 1) enqueueLifecycle(entry, "PENDING", entry.created());
        return entry.outcome();
    }

    public MovementOutcome get(long userId, UUID id) {
        return jdbc.query("SELECT * FROM transactions_service.customer_movements WHERE id = ? AND user_id = ?",
                        ENTRY, id, userId).stream().findFirst().map(Entry::outcome)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    @Scheduled(fixedDelayString = "${transactions.movements.delay-ms:500}")
    public void recoverScheduled() {
        if (!workerEnabled) return;
        recover();
        oldestPendingSeconds.set(jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))), 0)
                FROM transactions_service.customer_movements WHERE stage IN ('SCREENING', 'RESERVE', 'POST')
                """, Double.class));
    }

    public void recover() {
        for (int i = 0; i < 20; i++) {
            if (!Boolean.TRUE.equals(transaction.execute(ignored -> processNext()))) return;
        }
    }

    private boolean processNext() {
        var ready = jdbc.query("""
                SELECT * FROM transactions_service.customer_movements
                WHERE stage IN ('SCREENING', 'RESERVE', 'POST') AND next_attempt <= clock_timestamp()
                ORDER BY next_attempt, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, ENTRY);
        if (ready.isEmpty()) return false;
        Entry entry = ready.getFirst();
        switch (entry.stage()) {
            case SCREENING -> screen(entry);
            case RESERVE -> reserve(entry);
            case POST -> post(entry);
            default -> throw new IllegalStateException("Unexpected movement stage");
        }
        return true;
    }

    private void screen(Entry entry) {
        Intent intent = entry.intent();
        Long account = intent.from() != null ? intent.from() : intent.to();
        var decision = fraud.checkTransaction(account.toString(), Long.toString(entry.userId()),
                intent.amount().doubleValue(), intent.type().name(), "other");
        if (decision.getReason().startsWith("fraud-service unavailable")
                || "external-verification-unavailable".equals(decision.getReason())) {
            screeningOutages.increment();
            transition(entry, Stage.SCREENING, "SCREENING_UNAVAILABLE", "FRAUD_UNAVAILABLE", 30);
            return;
        }
        if (!decision.getApproved()) {
            transition(entry, Stage.REJECTED, "SCREENING_REJECTED", "FRAUD_REJECTED", 0);
            return;
        }
        try {
            if (intent.type() == MovementIntent.TypeEnum.TRANSFER) {
                compliance.verifyTransferCompliance(intent.from(), intent.to());
            }
        } catch (RuntimeException unavailable) {
            screeningOutages.increment();
            transition(entry, Stage.SCREENING, "SCREENING_UNAVAILABLE", "COMPLIANCE_UNRESOLVED", 30);
            return;
        }
        transition(entry, Stage.RESERVE, null, "APPROVED", 0);
    }

    private void reserve(Entry entry) {
        try {
            apply(entry, Action.RESERVE);
        } catch (StatusRuntimeException failure) {
            String rejection = switch (failure.getStatus().getCode()) {
                case NOT_FOUND -> "ACCOUNT_NOT_FOUND";
                case ABORTED -> "Account is not active".equals(failure.getStatus().getDescription())
                        ? "ACCOUNT_INACTIVE" : null;
                case FAILED_PRECONDITION -> "CURRENCY_MISMATCH".equals(failure.getStatus().getDescription())
                        ? "CURRENCY_MISMATCH" : "INSUFFICIENT_AVAILABLE_FUNDS";
                default -> null;
            };
            if (rejection != null) transition(entry, Stage.REJECTED, rejection, null, 0);
            else transition(entry, Stage.RESERVE, "LEDGER_UNRESOLVED", null, 15);
            return;
        }
        transition(entry, Stage.POST, null, null, 0);
    }

    private void post(Entry entry) {
        try {
            apply(entry, Action.COMMIT);
        } catch (StatusRuntimeException failure) {
            transition(entry, Stage.POST, "LEDGER_UNRESOLVED", null, 15);
            return;
        }
        Intent intent = entry.intent();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO transactions_service.transactions
                    (user_id, from_account_id, to_account_id, amount, type, description, status, processed_at,
                     movement_posting, movement_id)
                VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', ?, TRUE, ?)
                ON CONFLICT (movement_id) DO NOTHING
                """, entry.userId(), intent.from(), intent.to(), intent.amount(), intent.type().name(),
                intent.description(), now.toLocalDateTime(), entry.id());
        Long ledgerId = jdbc.queryForObject("""
                SELECT id FROM transactions_service.transactions WHERE movement_id = ?
                """, Long.class, entry.id());
        Integer queued = jdbc.queryForObject("""
                SELECT count(*) FROM transactions_service.event_outbox WHERE transaction_id = ?
                """, Integer.class, ledgerId);
        if (queued == null || queued == 0) {
            Transaction legacy = new Transaction(entry.userId(), intent.from(), intent.to(), intent.amount(),
                    Transaction.TransactionType.valueOf(intent.type().name()), intent.description());
            legacy.setId(ledgerId);
            legacy.setStatus(Transaction.TransactionStatus.COMPLETED);
            legacy.setProcessedAt(now.toLocalDateTime());
            outbox.enqueue(legacy);
        }

        enqueueLifecycle(entry, "POSTED", now);
        jdbc.update("""
                UPDATE transactions_service.customer_movements SET stage = 'POSTED', reason_code = NULL, updated_at = ?
                WHERE id = ?
                """, now, entry.id());
        postingLatency.record(Duration.between(entry.created(), now));
    }

    private void enqueueLifecycle(Entry entry, String status, OffsetDateTime at) {
        Intent intent = entry.intent();
        UUID eventId = UUID.nameUUIDFromBytes((entry.id() + ":" + status).getBytes(StandardCharsets.UTF_8));
        int scale = Currency.getInstance(intent.currency()).getDefaultFractionDigits();
        ObjectNode money = json.createObjectNode()
                .put("currency", intent.currency())
                .put("amount", intent.amount().setScale(scale).toPlainString());
        ObjectNode event = json.createObjectNode()
                .put("schemaVersion", 1)
                .put("eventId", eventId.toString())
                .put("movementId", entry.id().toString())
                .put("type", intent.type().name())
                .put("status", status)
                .put("occurredAt", at.toInstant().toString())
                .set("money", money);
        if (intent.from() != null) {
            event.put("fromAccountId", intent.from());
        }
        if (intent.to() != null) {
            event.put("toAccountId", intent.to());
        }
        try {
            outbox.enqueueMovement(entry.id(), eventId.toString(), json.writeValueAsString(event));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize movement lifecycle event", failure);
        }
    }

    private void apply(Entry entry, Action action) {
        Intent intent = entry.intent();
        accounts.applyExact(entry.id(), action,
                new Movement(entry.userId(), intent.from(), intent.to(), intent.amount()), intent.currency());
    }

    private void transition(Entry entry, Stage stage, String reason, String screening, int retrySeconds) {
        jdbc.update("""
                UPDATE transactions_service.customer_movements
                SET stage = ?, reason_code = ?, screening_decision = COALESCE(?, screening_decision),
                    updated_at = clock_timestamp(), next_attempt = clock_timestamp() + ? * interval '1 second'
                WHERE id = ?
                """, stage.name(), reason, screening, retrySeconds, entry.id());
        if (screening != null) {
            jdbc.update("""
                    INSERT INTO transactions_service.movement_screening_audit AS audit (movement_id, decision, policy)
                    VALUES (?, ?, 'REQUIRE_SCREENING')
                    ON CONFLICT (movement_id, decision) DO UPDATE
                    SET attempts = audit.attempts + 1, last_seen = clock_timestamp()
                    """, entry.id(), screening);
        }
        if (stage == Stage.REJECTED) {
            enqueueLifecycle(entry, "REJECTED", OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    private static Intent normalize(MovementIntent input) {
        if (input == null || input.getType() == null || input.getMoney() == null) throw invalid();
        Long from = input.getFromAccountId();
        Long to = input.getToAccountId();
        boolean accountsValid = switch (input.getType()) {
            case DEPOSIT -> from == null && positive(to);
            case WITHDRAWAL -> positive(from) && to == null;
            case TRANSFER -> positive(from) && positive(to) && !Objects.equals(from, to);
        };
        String raw = input.getMoney().getAmount();
        String currency = input.getMoney().getCurrency();
        String description = input.getDescription();
        if (!accountsValid || currency == null || !currency.matches("[A-Z]{3}")
                || raw == null || raw.length() > 20 || !raw.matches("(0|[1-9][0-9]*)([.][0-9]+)?")
                || (description != null && description.length() > 255)) throw invalid();
        BigDecimal amount = new BigDecimal(raw);
        int currencyScale;
        try {
            currencyScale = Currency.getInstance(currency).getDefaultFractionDigits();
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH");
        }
        if (currencyScale < 0 || currencyScale > 2 || amount.scale() > currencyScale
                || amount.signum() <= 0 || amount.setScale(2, RoundingMode.UNNECESSARY).precision() > 19) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT");
        }
        return new Intent(input.getType(), from, to, currency, amount.setScale(2, RoundingMode.UNNECESSARY), description);
    }

    private static boolean positive(Long account) {
        return account != null && account > 0;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }
}
