package com.banking.transactionsservice.integration;

import com.banking.transactionsservice.client.AccountsServiceClient;
import com.banking.transactionsservice.client.FraudServiceClient;
import com.banking.transactionsservice.contract.model.MovementIntent;
import com.banking.transactionsservice.contract.model.MovementMoney;
import com.banking.transactionsservice.contract.model.MovementOutcome;
import com.banking.transactionsservice.service.CustomerMovements;
import com.banking.transactionsservice.service.MovementCoordinator.Action;
import fraud.v1.Fraud.FraudCheckResponse;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MICROSVC_TX_POSTGRES_URL", matches = ".+")
class CustomerMovementsPostgresTest {
    private static final long USER = 71231L;

    @Autowired private CustomerMovements movements;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AccountsServiceClient accounts;
    @MockitoBean private FraudServiceClient fraud;
    @Autowired private tools.jackson.databind.ObjectMapper json;

    @Test
    void exactAmountMustBeAJsonString() {
        assertThrows(tools.jackson.core.JacksonException.class, () -> json.readValue("""
                {"type":"DEPOSIT","toAccountId":91,"money":{"currency":"AUD","amount":25.50}}
                """, MovementIntent.class));
        assertEquals("25.50", json.readValue("""
                {"type":"DEPOSIT","toAccountId":91,"money":{"currency":"AUD","amount":"25.50"}}
                """, MovementIntent.class).getMoney().getAmount());
    }

    @Test
    void concurrentRetryAndLostAccountAcknowledgementRecoverTheSamePosting() {
        String key = UUID.randomUUID().toString();
        var attempts = IntStream.range(0, 8)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> movements.submit(USER, key, deposit("25.50"))))
                .toList();
        UUID id = attempts.getFirst().join().getId();
        for (var attempt : attempts) assertEquals(id, attempt.join().getId());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> movements.submit(USER, key, deposit("25.51"))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> movements.get(USER + 1, id)).getStatusCode().value());
        verifyNoInteractions(accounts, fraud);

        when(fraud.checkTransaction(anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(decision(false, "external-verification-unavailable"));
        movements.recover();
        assertEquals("SCREENING_UNAVAILABLE", movements.get(USER, id).getReasonCode());
        assertEquals(id, movements.submit(USER, key, deposit("25.5")).getId());
        verify(fraud, times(1)).checkTransaction(anyString(), anyString(), anyDouble(), anyString(), anyString());
        verifyNoInteractions(accounts);

        when(fraud.checkTransaction(anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(decision(true, "approved"));
        doThrow(Status.UNAVAILABLE.asRuntimeException()).doNothing()
                .when(accounts).applyExact(eq(id), eq(Action.COMMIT), any(), eq("AUD"));
        retry(id);
        movements.recover();
        assertEquals(MovementOutcome.StatusEnum.PENDING, movements.get(USER, id).getStatus());
        assertEquals(0, pendingEvents(id));

        retry(id);
        movements.recover();
        var posted = movements.submit(USER, key, deposit("25.50"));
        assertEquals(id, posted.getId());
        assertEquals(MovementOutcome.StatusEnum.POSTED, posted.getStatus());
        assertEquals(1, pendingEvents(id));
        verify(accounts, times(1)).applyExact(eq(id), eq(Action.RESERVE), any(), eq("AUD"));
        verify(accounts, times(2)).applyExact(eq(id), eq(Action.COMMIT), any(), eq("AUD"));
    }

    private void retry(UUID id) {
        jdbc.update("UPDATE transactions_service.customer_movements SET next_attempt = clock_timestamp() WHERE id = ?", id);
    }

    private int pendingEvents(UUID id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transactions_service.event_outbox
                WHERE movement_id = ? AND payload::jsonb->>'status' = 'POSTED'
                """,
                Integer.class, id);
    }

    private static FraudCheckResponse decision(boolean approved, String reason) {
        return FraudCheckResponse.newBuilder().setApproved(approved).setReason(reason).build();
    }

    private static MovementIntent deposit(String amount) {
        return new MovementIntent(MovementIntent.TypeEnum.DEPOSIT, new MovementMoney("AUD", amount)).toAccountId(91L);
    }
}
