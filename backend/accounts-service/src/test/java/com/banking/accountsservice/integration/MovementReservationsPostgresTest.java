package com.banking.accountsservice.integration;

import com.banking.accountsservice.generated.AccountCreateRequest;
import com.banking.accountsservice.generated.AccountType;
import com.banking.accountsservice.entity.Account.Status;
import com.banking.accountsservice.service.AccountService;
import com.banking.accountsservice.service.MovementReservations;
import com.banking.accountsservice.service.MovementReservations.Action;
import com.banking.accountsservice.service.MovementReservations.Movement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
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
    @LocalServerPort private int port;

    @Test
    void competingMovementsCannotBothReserveTheSameAccount() throws Exception {
        long from = account("100"), to = account("0");
        Movement debit = movement(from, to);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        boolean[] accepted = race(() -> reservations.apply(first, Action.RESERVE, debit),
                () -> reservations.apply(second, Action.RESERVE, debit));
        assertNotEquals(accepted[0], accepted[1]);
        UUID winner = accepted[0] ? first : second;
        reservations.apply(winner, Action.COMMIT, debit);
        reservations.apply(winner, Action.COMMIT, debit);
        assertBalances(from, to, "75", "25");
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
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> reservations.apply(UUID.randomUUID(), Action.RESERVE, debit)).getStatusCode().value());
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
        String payload = """
                {"userId":%d,"fromAccountId":%d,"toAccountId":%d,"amount":25.00}
                """.formatted(OWNER, from, to);
        String data = "account-movement:v1:" + operation + ":RESERVE:" + OWNER + ":" + from + ":" + to + ":25.00";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("synthetic-movement-signing-test-value".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/internal/account-movements/" + operation + "/RESERVE");
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                .header("X-Movement-Signature", signature);
        assertEquals(403, client.send(request.POST(HttpRequest.BodyPublishers.ofString(payload.replace("25.00", "26.00")))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(204, client.send(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        reservations.apply(operation, Action.COMPENSATE, movement(from, to));
        assertBalances(from, to, "100", "0");
    }

    @Test
    void outboundRailChecksCurrencyAndBooksTheSameOperationOnlyOnce() throws Exception {
        long usd = account("100", "USD"), aud = account("100", "AUD");
        UUID operation = UUID.randomUUID();
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(422, outbound(client, UUID.randomUUID(), Action.RESERVE, usd, "outbound-aud-movement:v1:"));
            assertEquals(403, outbound(client, operation, Action.RESERVE, aud, "account-movement:v1:"));
            assertEquals(204, outbound(client, operation, Action.RESERVE, aud, "outbound-aud-movement:v1:"));
            assertBalances(aud, usd, "100", "100");
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> reservations.apply(UUID.randomUUID(), Action.RESERVE, movement(aud, usd)))
                    .getStatusCode().value());
            assertEquals(204, outbound(client, operation, Action.COMMIT, aud, "outbound-aud-movement:v1:"));
            assertEquals(204, outbound(client, operation, Action.COMMIT, aud, "outbound-aud-movement:v1:"));
            assertEquals(409, outbound(client, operation, Action.COMPENSATE, aud, "outbound-aud-movement:v1:"));
            assertBalances(aud, usd, "75", "100");

            UUID rejected = UUID.randomUUID();
            assertEquals(204, outbound(client, rejected, Action.RESERVE, aud, "outbound-aud-movement:v1:"));
            assertEquals(204, outbound(client, rejected, Action.COMPENSATE, aud, "outbound-aud-movement:v1:"));
            assertEquals(204, outbound(client, rejected, Action.COMPENSATE, aud, "outbound-aud-movement:v1:"));
            assertEquals(409, outbound(client, rejected, Action.COMMIT, aud, "outbound-aud-movement:v1:"));
            assertBalances(aud, usd, "75", "100");
        }
    }

    private int outbound(HttpClient client, UUID operation, Action action, long account, String scope) throws Exception {
        String payload = """
                {"userId":%d,"fromAccountId":%d,"toAccountId":null,"amount":25.00}
                """.formatted(OWNER, account);
        String canonical = scope + operation + ":" + action + ":" + OWNER + ":" + account + ":null:25.00";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("synthetic-movement-signing-test-value".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/internal/account-movements/outbound-aud/" + operation + "/" + action);
        var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                .header("X-Movement-Signature", signature).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private long account(String balance) {
        return account(balance, "USD");
    }

    private long account(String balance, String currency) {
        var request = new AccountCreateRequest(AccountType.CHECKING);
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
                assertEquals(409, rejected.getStatusCode().value());
                return false;
            }
        };
    }
}
