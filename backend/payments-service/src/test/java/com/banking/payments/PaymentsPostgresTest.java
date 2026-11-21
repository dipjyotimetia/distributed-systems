package com.banking.payments;

import com.banking.payments.clearing.ClearingRecord;
import com.banking.payments.clearing.SettlementFile;
import com.banking.payments.settlement.BusinessDay;
import com.banking.payments.settlement.Reconciliation;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "PAYMENTS_DB_URL", matches = ".+")
class PaymentsPostgresTest {
    private static final String SECRET = "synthetic-payments-smoke-test-key-123456789";
    private static final String BODY = """
            {"accountId":10,"destination":"DEST00000001","amountMinor":25}
            """;

    @Test
    void commitsOnePaymentAndWorkflowStartForConcurrentSubmissions() {
        try (var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(PaymentsApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("JWT_SECRET=" + SECRET, "server.port=0").run()) {
            URI server = URI.create("http://127.0.0.1:" + context.getWebServer().getPort());
            String key = UUID.randomUUID().toString();
            String customer = token(71, Instant.now().plusSeconds(60));
            String stranger = token(72, Instant.now().plusSeconds(60));

            try (HttpClient client = HttpClient.newHttpClient()) {
                var results = IntStream.range(0, 8).mapToObj(index ->
                        client.sendAsync(post(server, customer, key, BODY), HttpResponse.BodyHandlers.ofString()))
                        .toList().stream().map(CompletableFuture::join).toList();
                var saved = results.getFirst();
                String location = saved.headers().firstValue("location").orElseThrow();
                results.forEach(response -> {
                    assertEquals(202, response.statusCode());
                    assertEquals(saved.body(), response.body());
                });
                assertEquals(400, status(client, post(server, customer, key, BODY.replace("25", "25.5"))));
                assertEquals(409, status(client, post(server, customer, key, BODY.replace("25", "26"))));
                assertEquals(401, status(client, post(server, token(71, Instant.now().minusSeconds(60)), key, BODY)));
                assertEquals(200, status(client, read(server.resolve(location), customer)));
                assertEquals(404, status(client, read(server.resolve(location), stranger)));

                var db = context.getBean(JdbcTemplate.class);
                UUID id = UUID.fromString(location.substring("/api/payments/".length()));
                var owned = client.sendAsync(read(server.resolve("/api/payments"), customer), HttpResponse.BodyHandlers.ofString()).join();
                var other = client.sendAsync(read(server.resolve("/api/payments"), stranger), HttpResponse.BodyHandlers.ofString()).join();
                assertEquals(200, owned.statusCode());
                assertTrue(owned.body().contains(id.toString()));
                assertEquals(200, other.statusCode());
                assertFalse(other.body().contains(id.toString()));
                assertTrue(db.queryForObject("SELECT trace_parent FROM payments.payment WHERE id = ?", String.class, id)
                        .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"));
                var scrape = client.sendAsync(HttpRequest.newBuilder(server.resolve("/actuator/prometheus")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).join();
                assertEquals(200, scrape.statusCode());
                assertTrue(scrape.body().contains("payments_oldest_pending_seconds"));
                assertTrue(scrape.body().contains("payments_reconciliation_discrepancies"));
                assertEquals(1, db.queryForObject("""
                        SELECT count(*) FROM payments.outbox
                        WHERE payment_id = ? AND destination = 'WORKFLOW' AND published_at IS NULL
                        """, Integer.class, id));
                assertEquals(1, db.queryForObject("""
                        SELECT count(*) FROM payments.outbox o JOIN payments.transition t ON t.id = o.id
                        WHERE o.payment_id = ? AND o.destination = 'KAFKA' AND t.status = 'PENDING'
                        """, Integer.class, id));
                assertEquals(1, db.queryForObject("""
                        SELECT count(*) FROM payments.transition WHERE payment_id = ? AND status = 'PENDING'
                        """, Integer.class, id));
            }
        }
    }

    @Test
    void retainsTheHoldOnTimeoutAndSettlesOnceWhenEvidenceArrives() {
        try (var context = new SpringApplicationBuilder(PaymentsApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("JWT_SECRET=" + SECRET, "server.port=0").run()) {
            var db = context.getBean(JdbcTemplate.class);
            var store = context.getBean(PaymentStore.class);
            UUID id = store.initiate(71, UUID.randomUUID().toString(),
                    new PaymentStore.Instruction(10, "DEST00000001", 25)).id();
            PaymentChecks checks = mock(PaymentChecks.class);
            when(checks.approved(71, 10, 25, "DEST00000001")).thenReturn(true);
            when(checks.move(id, PaymentChecks.Movement.RESERVE, 71, 10, 25)).thenReturn(true);
            when(checks.move(id, PaymentChecks.Movement.COMMIT, 71, 10, 25)).thenReturn(true);
            var steps = new PaymentSteps(db, context.getBean(TransactionTemplate.class),
                    context.getBean(PaymentLifecycle.class), checks, context.getBean(PaymentTracing.class), Duration.ofMinutes(2));

            assertFalse(steps.advance(id));
            assertFalse(steps.advance(id));
            assertEquals(1, db.queryForObject("""
                    SELECT count(*) FROM payments.outbox WHERE payment_id = ? AND destination = 'MQ'
                    """, Integer.class, id));
            db.update("UPDATE payments.payment SET reserved_at = now() - interval '3 minutes' WHERE id = ?", id);

            assertFalse(steps.advance(id));
            assertFalse(steps.advance(id));
            assertEquals("REVIEW_REQUIRED", store.findOwned(71, id).status());
            assertEquals(1, db.queryForObject("""
                    SELECT count(*) FROM payments.transition WHERE payment_id = ? AND status = 'REVIEW_REQUIRED'
                    """, Integer.class, id));

            db.update("""
                    INSERT INTO payments.clearing_inbox (payment_id, kind, amount_minor, reference, business_date)
                    VALUES (?, 'SETTLED', 25, 'C00000000001', DATE '2026-11-20')
                    """, id);
            assertTrue(steps.advance(id));
            assertTrue(steps.advance(id));
            assertEquals("SETTLED", store.findOwned(71, id).status());
            assertEquals(LocalDate.of(2026, 11, 20), store.findOwned(71, id).settlementBusinessDate());
            verify(checks).move(id, PaymentChecks.Movement.RESERVE, 71, 10, 25);
            verify(checks).move(id, PaymentChecks.Movement.COMMIT, 71, 10, 25);
        }
    }

    @Test
    void reconcilesOneResultPerRecordAndRetainsDiscrepanciesOnReplay() {
        try (var context = new SpringApplicationBuilder(PaymentsApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("JWT_SECRET=" + SECRET, "server.port=0", "PAYMENTS_OPERATOR_IDS=71").run()) {
            var db = context.getBean(JdbcTemplate.class);
            var store = context.getBean(PaymentStore.class);
            var reconcile = context.getBean(Reconciliation.class);
            var calendar = context.getBean(BusinessDay.class);
            int sequence = db.queryForObject("""
                    SELECT coalesce(max(file_sequence), 0) + 1 FROM payments.reconciliation_run
                    """, Integer.class);
            LocalDate date = LocalDate.of(1900, 1, 1).plusDays(sequence);
            UUID matched = newPayment(store);
            UUID mismatched = newPayment(store);
            UUID missing = newPayment(store);
            UUID duplicate = UUID.randomUUID();
            db.update("UPDATE payments.payment SET reserved_at = now() WHERE id = ?", matched);
            db.update("""
                    UPDATE payments.payment SET status = 'SETTLED', settlement_business_date = ? WHERE id = ?
                    """, date, missing);
            db.update("""
                    INSERT INTO payments.transition (id, payment_id, status, reason, occurred_at)
                    VALUES (?, ?, 'SETTLED', 'TEST_DELAYED_REPLY', ?)
                    """, UUID.randomUUID(), missing, calendar.end(date.plusDays(2)).atOffset(ZoneOffset.UTC));
            var file = new SettlementFile(date, sequence, List.of(settled(matched, 25), settled(mismatched, 26),
                    settled(duplicate, 25), settled(duplicate, 25), settled(UUID.randomUUID(), 25)));
            assertTrue(reconcile.run(date, file.encode()));
            assertFalse(reconcile.run(date, file.encode()));
            assertThrows(IllegalStateException.class, () -> reconcile.run(date, new SettlementFile(date, sequence, List.of()).encode()));
            assertEquals(List.of("MATCHED", "AMOUNT_MISMATCH", "DUPLICATE", "DUPLICATE", "MISSING_PAYMENT", "MISSING_RECORD"),
                    db.queryForList("""
                            SELECT outcome FROM payments.reconciliation_result WHERE business_date = ? ORDER BY record_number
                            """, String.class, date));
            assertEquals(1, db.queryForObject("""
                    SELECT count(*) FROM payments.clearing_inbox WHERE payment_id = ? AND kind = 'SETTLED'
                    """, Integer.class, matched));
            UUID arrivedAfterImport = newPayment(store);
            db.update("""
                    UPDATE payments.payment SET status = 'SETTLED', settlement_business_date = ? WHERE id = ?
                    """, date, arrivedAfterImport);
            assertEquals(1, reconcile.recordLateDiscrepancies());
            assertEquals(0, reconcile.recordLateDiscrepancies());
            assertFalse(reconcile.run(date, file.encode()));
            assertEquals("MISSING_RECORD", db.queryForObject("""
                    SELECT outcome FROM payments.reconciliation_result WHERE business_date = ? AND payment_id = ?
                    """, String.class, date, arrivedAfterImport));
            var serverContext = (ServletWebServerApplicationContext) context;
            URI queue = URI.create("http://127.0.0.1:" + serverContext.getWebServer().getPort()
                    + "/api/operations/payments/reconciliation");
            try (HttpClient client = HttpClient.newHttpClient()) {
                var operator = read(queue, token(71, Instant.now().plusSeconds(60)));
                var response = client.sendAsync(operator, HttpResponse.BodyHandlers.ofString()).join();
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("AMOUNT_MISMATCH"));
                assertEquals(403, status(client, read(queue, token(72, Instant.now().plusSeconds(60)))));
            }
        }
    }

    @Test
    void independentSettlementEvidenceResolvesConflictingBrokerReplies() {
        try (var context = new SpringApplicationBuilder(PaymentsApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("JWT_SECRET=" + SECRET, "server.port=0").run()) {
            var db = context.getBean(JdbcTemplate.class);
            var store = context.getBean(PaymentStore.class);
            UUID id = newPayment(store);
            int sequence = db.queryForObject("""
                    SELECT coalesce(max(file_sequence), 0) + 1 FROM payments.reconciliation_run
                    """, Integer.class);
            LocalDate date = LocalDate.of(1900, 1, 1).plusDays(sequence);
            db.update("UPDATE payments.payment SET screened_at = now(), reserved_at = now() WHERE id = ?", id);
            db.update("""
                    INSERT INTO payments.clearing_inbox (payment_id, kind, amount_minor, reference, business_date)
                    VALUES (?, 'REJECTED', 25, 'C00000000001', NULL), (?, 'SETTLED', 25, 'C00000000001', ?)
                    """, id, id, date);

            var checks = mock(PaymentChecks.class);
            var steps = new PaymentSteps(db, context.getBean(TransactionTemplate.class),
                    context.getBean(PaymentLifecycle.class), checks, context.getBean(PaymentTracing.class), Duration.ofMinutes(2));
            assertFalse(steps.advance(id));
            assertEquals("REVIEW_REQUIRED", store.findOwned(71, id).status());
            verify(checks, never()).move(id, PaymentChecks.Movement.COMMIT, 71, 10, 25);
            verify(checks, never()).move(id, PaymentChecks.Movement.COMPENSATE, 71, 10, 25);

            byte[] independentFile = new SettlementFile(date, sequence, List.of(settled(id, 25))).encode();
            assertTrue(context.getBean(Reconciliation.class).run(date, independentFile));
            when(checks.move(id, PaymentChecks.Movement.COMMIT, 71, 10, 25)).thenReturn(true);
            assertTrue(steps.advance(id));
            assertTrue(steps.advance(id));
            verify(checks).move(id, PaymentChecks.Movement.COMMIT, 71, 10, 25);
            assertEquals("SETTLED", store.findOwned(71, id).status());
            assertEquals("RECONCILED_SETTLEMENT", db.queryForObject("""
                    SELECT reason FROM payments.transition WHERE payment_id = ? AND status = 'SETTLED'
                    """, String.class, id));
        }
    }

    private static UUID newPayment(PaymentStore store) {
        return store.initiate(71, UUID.randomUUID().toString(),
                new PaymentStore.Instruction(10, "DEST00000001", 25)).id();
    }

    private static ClearingRecord settled(UUID id, long cents) {
        return new ClearingRecord(ClearingRecord.Kind.SETTLED, id, cents, "C00000000001");
    }

    private static HttpRequest post(URI server, String token, String key, String body) {
        return HttpRequest.newBuilder(server.resolve("/api/payments"))
                .header("Authorization", "Bearer " + token)
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static HttpRequest read(URI uri, String token) {
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).GET().build();
    }

    private static int status(HttpClient client, HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).join().statusCode();
    }

    private static String token(long id, Instant expires) {
        return Jwts.builder().claim("userId", id).expiration(Date.from(expires))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }
}
