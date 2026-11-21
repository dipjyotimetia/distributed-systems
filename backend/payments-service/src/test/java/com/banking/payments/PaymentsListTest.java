package com.banking.payments;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentsListTest {
    private static final String SECRET = "synthetic-payments-smoke-test-key-123456789";

    @Test
    void listsOnlyTheAuthenticatedCustomer() {
        PaymentStore store = mock(PaymentStore.class);
        UUID id = UUID.fromString("597a4c24-019f-4ed6-884f-bf7cf8486f65");
        var owned = new PaymentStore.Payment(id, new PaymentStore.Instruction(10, "DEST00000001", 25),
                "PENDING", null, OffsetDateTime.parse("2026-11-21T00:00:00Z"), null);
        when(store.listOwned(71)).thenReturn(List.of(owned));
        when(store.listOwned(72)).thenReturn(List.of());
        var controller = new PaymentsController(store, mock(JdbcTemplate.class), SECRET, "");

        assertEquals(List.of(owned), controller.list("Bearer " + token(71)).getBody());
        assertEquals(List.of(), controller.list("Bearer " + token(72)).getBody());
        verify(store).listOwned(71);
        verify(store).listOwned(72);
    }

    private static String token(long id) {
        return Jwts.builder().claim("userId", id).expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }
}
