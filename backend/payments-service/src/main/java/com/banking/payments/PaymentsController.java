package com.banking.payments;

import com.banking.payments.PaymentStore.Instruction;
import com.banking.payments.PaymentStore.Payment;
import com.banking.payments.contract.ContractJson;
import com.banking.payments.contract.PaymentsApi;
import com.banking.payments.clearing.ClearingRecord;
import io.jsonwebtoken.JwtException;
import io.opentelemetry.api.trace.Span;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Profile("!clearing & !payment-worker & !reconciliation")
@RestController
public class PaymentsController implements PaymentsApi {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern DESTINATION = Pattern.compile("[A-Z0-9]{12}");

    private final PaymentStore store;
    private final JwtParser jwt;
    private final JdbcTemplate db;
    private final Set<Long> operators;
    private final ContractJson contractJson;

    public PaymentsController(PaymentStore store, JdbcTemplate db, ContractJson contractJson, @Value("${JWT_SECRET}") String secret,
                              @Value("${PAYMENTS_OPERATOR_IDS:}") String operatorIds) {
        this.store = store;
        this.db = db;
        this.contractJson = contractJson;
        this.operators = Set.copyOf(Arrays.stream(operatorIds.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .map(Long::valueOf).toList());
        this.jwt = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .sig().clear().add(Jwts.SIG.HS256).and().build();
    }

    @Override
    public ResponseEntity<com.banking.payments.contract.model.Payment> initiatePayment(String key,
            com.banking.payments.contract.model.PaymentInstruction paymentInstruction) {
        Instruction instruction = contractJson.convert(paymentInstruction, Instruction.class);
        long customer = customer(authorization());
        if (!IDEMPOTENCY_KEY.matcher(key).matches() || instruction.accountId() < 1
                || instruction.destination() == null || !DESTINATION.matcher(instruction.destination()).matches()
                || instruction.amountMinor() < 1 || instruction.amountMinor() > ClearingRecord.MAX_AMOUNT_MINOR) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid outbound payment");
        }
        Payment payment = store.initiate(customer, key, instruction);
        Span.current().setAttribute("payment.id", payment.id().toString());
        return ResponseEntity.accepted().location(URI.create("/api/v1/payments/" + payment.id()))
                .cacheControl(CacheControl.noStore()).body(contractJson.convert(payment, com.banking.payments.contract.model.Payment.class));
    }

    @Override
    public ResponseEntity<List<com.banking.payments.contract.model.Payment>> listPayments() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(store.listOwned(customer(authorization())).stream()
                        .map(payment -> contractJson.convert(payment, com.banking.payments.contract.model.Payment.class))
                        .toList());
    }

    @Override
    public ResponseEntity<com.banking.payments.contract.model.Payment> getPayment(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(contractJson.convert(store.findOwned(customer(authorization()), id), com.banking.payments.contract.model.Payment.class));
    }

    public record Discrepancy(java.time.LocalDate businessDate, int recordNumber, UUID paymentId, String outcome,
                              Long expectedAmountMinor, Long observedAmountMinor, String observedReference) {}

    @Override
    public ResponseEntity<List<com.banking.payments.contract.model.Discrepancy>> listDiscrepancies(Integer offset) {
        int start = offset == null ? 0 : offset;
        if (!operators.contains(customer(authorization()))) throw new ResponseStatusException(FORBIDDEN);
        if (start < 0) throw new ResponseStatusException(BAD_REQUEST);
        var results = db.query("""
                SELECT business_date, record_number, payment_id, outcome, expected_amount_minor,
                       observed_amount_minor, observed_reference FROM payments.reconciliation_result
                WHERE outcome <> 'MATCHED' ORDER BY business_date DESC, record_number LIMIT 100 OFFSET ?
                """, (row, index) -> new Discrepancy(row.getObject(1, java.time.LocalDate.class), row.getInt(2),
                row.getObject(3, UUID.class), row.getString(4), row.getObject(5, Long.class),
                row.getObject(6, Long.class), row.getString(7)), start);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.stream()
                .map(row -> contractJson.convert(row, com.banking.payments.contract.model.Discrepancy.class)).toList());
    }

    private static String authorization() {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attributes.getRequest().getHeader("Authorization");
    }

    private long customer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        try {
            var claims = jwt.parseSignedClaims(authorization.substring(7)).getPayload();
            long id = Long.parseLong(String.valueOf(claims.get("userId")));
            if (id > 0 && claims.getExpiration() != null) {
                return id;
            }
        } catch (JwtException | IllegalArgumentException invalidToken) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        throw new ResponseStatusException(UNAUTHORIZED);
    }
}
