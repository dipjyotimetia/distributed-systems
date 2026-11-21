package com.banking.accountsservice.integration;

import com.banking.accountsservice.dto.AccountCreateRequest;
import com.banking.accountsservice.entity.Account.Status;
import com.banking.accountsservice.grpc.LedgerGrpcServer;
import com.banking.accountsservice.service.AccountService;
import com.banking.accountsservice.service.MovementReservations;
import com.banking.accountsservice.service.StatementExportService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import ledger.v1.AccountLedgerGrpc;
import ledger.v1.MovementRequest;
import com.banking.accountsservice.service.MovementReservations.Action;
import com.banking.accountsservice.service.MovementReservations.Movement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "movement.secret=synthetic-movement-signing-test-value",
        "spring.datasource.url=${MICROSVC_ACCOUNTS_POSTGRES_URL}",
        "spring.datasource.username=${MICROSVC_POSTGRES_USER:postgres}",
        "spring.datasource.password=${MICROSVC_POSTGRES_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.url=${MICROSVC_ACCOUNTS_POSTGRES_URL}",
        "spring.flyway.user=${MICROSVC_POSTGRES_USER:postgres}",
        "spring.flyway.password=${MICROSVC_POSTGRES_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "MICROSVC_ACCOUNTS_POSTGRES_URL", matches = ".+")
class MovementReservationsPostgresTest {
    private static final long OWNER = 71021L;

    @Autowired private AccountService accounts;
    @Autowired private MovementReservations reservations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LedgerGrpcServer ledger;
    @Autowired private StatementExportService statements;

    @Test
    void competingHoldsCannotExceedAvailableFunds() throws Exception {
        long from = account("100"), to = account("0");
        Movement debit = new Movement(OWNER, from, to, new BigDecimal("75.00"));
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        boolean[] accepted = race(() -> reservations.apply(first, Action.RESERVE, debit),
                () -> reservations.apply(second, Action.RESERVE, debit));
        assertNotEquals(accepted[0], accepted[1]);
        UUID winner = accepted[0] ? first : second;
        reservations.apply(winner, Action.COMMIT, debit);
        reservations.apply(winner, Action.COMMIT, debit);
        assertBalances(from, to, "25", "75");
    }

    @Test
    void unrelatedDebitPostsWhileAnOutboundHoldRemainsActive() {
        long from = account("100", "AUD"), to = account("0", "AUD");
        UUID payment = UUID.randomUUID(), transfer = UUID.randomUUID();
        Movement outbound = new Movement(OWNER, from, null, new BigDecimal("60.00"));
        Movement internal = movement(from, to);
        reservations.applyOutbound(payment, Action.RESERVE, outbound);
        reservations.applyExact(transfer, Action.RESERVE, internal, "AUD", false);
        reservations.applyExact(transfer, Action.COMMIT, internal, "AUD", false);
        reservations.applyExact(transfer, Action.COMMIT, internal, "AUD", false);
        assertBalances(from, to, "75", "25");
        reservations.applyOutbound(payment, Action.COMMIT, outbound);
        assertBalances(from, to, "15", "25");
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts_service.account_postings WHERE operation_id IN (?, ?)
                """, Integer.class, payment, transfer));
    }

    @Test
    void periodIncludesPostingAtStartExcludesEndAndIgnoresActiveHolds() {
        long account = account("100", "AUD");
        UUID settled = UUID.randomUUID(), later = UUID.randomUUID(), held = UUID.randomUUID();
        Movement debit = new Movement(OWNER, account, null, new BigDecimal("25.00"));
        reservations.applyOutbound(settled, Action.RESERVE, debit);
        reservations.applyOutbound(settled, Action.COMMIT, debit);
        reservations.applyOutbound(later, Action.RESERVE, debit);
        reservations.applyOutbound(later, Action.COMMIT, debit);
        reservations.applyOutbound(held, Action.RESERVE, debit);

        OffsetDateTime start = timestamp(settled);
        OffsetDateTime end = timestamp(later);
        var statement = statements.generatePeriod(account, OWNER, start, end);
        assertEquals("100.00", statement.openingBalance());
        assertEquals("75.00", statement.closingBalance());
        assertEquals(1, statement.entries().size());
        assertEquals(settled, statement.entries().getFirst().operationId());
        assertEquals("OUTBOUND_PAYMENT", statement.entries().getFirst().source());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> statements.generatePeriod(account, OWNER + 1, start, end)).getStatusCode().value());
    }

    private OffsetDateTime timestamp(UUID operation) {
        return jdbc.queryForObject("SELECT posted_at FROM accounts_service.account_postings WHERE operation_id = ?",
                OffsetDateTime.class, operation);
    }

    @Test
    void closureAndIncomingTransferCannotBothSucceed() throws Exception {
        long from = account("100"), to = account("0");
        Movement debit = movement(from, to);
        UUID operation = UUID.randomUUID();
        boolean[] accepted = race(() -> reservations.apply(operation, Action.RESERVE, debit),
                () -> accounts.updateAccount(to, OWNER, null, Status.CLOSED));
        assertNotEquals(accepted[0], accepted[1]);
        if (accepted[0]) reservations.apply(operation, Action.COMPENSATE, debit);
        assertEquals(Status.CLOSED, accounts.updateAccount(to, OWNER, null, Status.CLOSED).getStatus());
        assertEquals(Status.CLOSED, accounts.updateAccount(to, OWNER, null, Status.CLOSED).getStatus());
        assertBalances(from, to, "100", "0");
    }

    @Test
    void frozenAccountRejectsMovementsAndNonzeroAccountRejectsClosure() {
        long from = account("100"), to = account("0");
        Movement debit = movement(from, to);
        UUID operation = UUID.randomUUID();
        accounts.updateAccount(from, OWNER, null, Status.FROZEN);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> reservations.apply(operation, Action.RESERVE, debit)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> accounts.updateAccount(from, OWNER, null, Status.CLOSED)).getStatusCode().value());
        accounts.updateAccount(from, OWNER, null, Status.ACTIVE);
        reservations.apply(operation, Action.RESERVE, debit);
        reservations.apply(operation, Action.COMPENSATE, debit);
        assertBalances(from, to, "100", "0");
    }

    @Test
    void secondBalanceWriteFailureRollsBackBothWritesAndPreservesReservation() {
        long from = account("100"), to = account("0");
        Movement debit = movement(from, to);
        UUID operation = UUID.randomUUID();
        reservations.apply(operation, Action.RESERVE, debit);
        String constraint = "reject_fixture_credit_" + to;
        jdbc.execute("ALTER TABLE accounts_service.accounts ADD CONSTRAINT " + constraint
                + " CHECK (id <> " + to + " OR balance = 0)");
        try {
            assertThrows(RuntimeException.class, () -> reservations.apply(operation, Action.COMMIT, debit));
            assertBalances(from, to, "100", "0");
            UUID independent = UUID.randomUUID();
            reservations.apply(independent, Action.RESERVE, debit);
            reservations.apply(independent, Action.COMPENSATE, debit);
        } finally {
            jdbc.execute("ALTER TABLE accounts_service.accounts DROP CONSTRAINT " + constraint);
        }

        reservations.apply(operation, Action.COMPENSATE, debit);
        reservations.apply(operation, Action.COMPENSATE, debit);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> reservations.apply(operation, Action.RESERVE, debit)).getStatusCode().value());
        UUID retry = UUID.randomUUID();
        reservations.apply(retry, Action.RESERVE, debit);
        reservations.apply(retry, Action.COMMIT, debit);
        assertBalances(from, to, "75", "25");
    }

    @Test
    void internalMovementEndpointRequiresMatchingSignature() throws Exception {
        long from = account("100"), to = account("0");
        UUID operation = UUID.randomUUID();
        assertEquals(403, callLedger(false, operation, Action.RESERVE, from, to, "26.00", "account-movement:v1:", "25.00"));
        assertEquals(204, callLedger(false, operation, Action.RESERVE, from, to, "25.00", "account-movement:v1:", "25.00"));
        reservations.apply(operation, Action.COMPENSATE, movement(from, to));
        assertBalances(from, to, "100", "0");
    }

    @Test
    void outboundRailChecksCurrencyAndBooksTheSameOperationOnlyOnce() throws Exception {
        long usd = account("100", "USD"), aud = account("100", "AUD");
        UUID operation = UUID.randomUUID();
        assertEquals(422, callLedger(true, UUID.randomUUID(), Action.RESERVE, usd, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertEquals(403, callLedger(true, operation, Action.RESERVE, aud, null, "25.00", "account-movement:v1:", "25.00"));
        assertEquals(204, callLedger(true, operation, Action.RESERVE, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertBalances(aud, usd, "100", "100");
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> reservations.apply(UUID.randomUUID(), Action.RESERVE, movement(aud, usd)))
                .getStatusCode().value());
        assertEquals(204, callLedger(true, operation, Action.COMMIT, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertEquals(204, callLedger(true, operation, Action.COMMIT, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertEquals(409, callLedger(true, operation, Action.COMPENSATE, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertBalances(aud, usd, "75", "100");

        UUID rejected = UUID.randomUUID();
        assertEquals(204, callLedger(true, rejected, Action.RESERVE, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertEquals(204, callLedger(true, rejected, Action.COMPENSATE, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertEquals(204, callLedger(true, rejected, Action.COMPENSATE, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertEquals(409, callLedger(true, rejected, Action.COMMIT, aud, null, "25.00", "outbound-aud-movement:v1:", "25.00"));
        assertBalances(aud, usd, "75", "100");
    }

    private int callLedger(boolean outbound, UUID operation, Action action, long account, Long to, String amount,
                           String scope, String signedAmount) throws Exception {
        String canonical = scope + operation + ":" + action + ":" + OWNER + ":" + account + ":"
                + (to == null ? "null" : to) + ":" + signedAmount;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("synthetic-movement-signing-test-value".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        MovementRequest.Builder request = MovementRequest.newBuilder().setOperationId(operation.toString())
                .setAction(action.name()).setUserId(OWNER).setFromAccountId(account).setAmount(amount);
        if (to != null) {
            request.setToAccountId(to);
        }
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-movement-signature", Metadata.ASCII_STRING_MARSHALLER), signature);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", ledger.port()).usePlaintext().build();
        try {
            var stub = AccountLedgerGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            if (outbound) {
                stub.applyOutboundAud(request.build());
            } else {
                stub.applyMovement(request.build());
            }
            return 204;
        } catch (StatusRuntimeException failure) {
            return switch (failure.getStatus().getCode()) {
                case PERMISSION_DENIED -> 403;
                case NOT_FOUND -> 404;
                case ABORTED -> 409;
                case FAILED_PRECONDITION -> 422;
                case INVALID_ARGUMENT -> 400;
                default -> 500;
            };
        } finally {
            channel.shutdownNow();
        }
    }

    private long account(String balance) {
        return account(balance, "USD");
    }

    private long account(String balance, String currency) {
        var request = new AccountCreateRequest();
        request.setAccountType("CHECKING");
        request.setCurrency(currency);
        request.setInitialBalance(new BigDecimal(balance));
        return accounts.createAccount(request, OWNER).getId();
    }

    private static Movement movement(long from, long to) {
        return new Movement(OWNER, from, to, new BigDecimal("25.00"));
    }

    private void assertBalances(long from, long to, String debit, String credit) {
        assertEquals(0, accounts.getAccountBalance(from, OWNER).getBalance().compareTo(new BigDecimal(debit)));
        assertEquals(0, accounts.getAccountBalance(to, OWNER).getBalance().compareTo(new BigDecimal(credit)));
    }

    private static boolean[] race(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var a = threads.submit(compete(start, first));
            var b = threads.submit(compete(start, second));
            start.countDown();
            return new boolean[]{a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)};
        } finally {
            threads.shutdownNow();
        }
    }

    private static Callable<Boolean> compete(CountDownLatch start, Runnable action) {
        return () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
                action.run();
                return true;
            } catch (ResponseStatusException rejected) {
                assertTrue(java.util.Set.of(409, 422).contains(rejected.getStatusCode().value()));
                return false;
            }
        };
    }
}
