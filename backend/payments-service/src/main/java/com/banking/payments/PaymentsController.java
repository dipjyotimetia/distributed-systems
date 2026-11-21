package com.banking.payments;

import com.banking.payments.PaymentStore.Instruction;
import com.banking.payments.PaymentStore.Payment;
import com.banking.payments.contract.ContractJson;
import com.banking.payments.contract.PaymentsApi;
import com.banking.payments.contract.PaymentReviewApi;
import com.banking.payments.contract.model.PaymentReview;
import com.banking.payments.contract.model.PaymentReviewError;
import com.banking.payments.contract.model.PaymentReviewEvidence;
import com.banking.payments.contract.model.PaymentResolution;
import com.banking.payments.contract.model.PaymentResolutionRequest;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@Profile("!clearing & !payment-worker & !reconciliation")
@RestController
public class PaymentsController implements PaymentsApi, PaymentReviewApi {
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
        requireOperator();
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

    private record ReviewTarget(String status, long revision, long amount, String reference, boolean reserved) {}
    private record Decision(UUID id, UUID payment, UUID evidence, long revision, String action, String reason,
                            boolean applied) {
        PaymentResolution representation() {
            return new PaymentResolution(id, applied
                    ? PaymentResolution.StatusEnum.APPLIED : PaymentResolution.StatusEnum.PENDING);
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ResponseEntity<List<PaymentReview>> listPaymentReviews(Integer offset) {
        requireOperator();
        int start = offset == null ? 0 : offset;
        if (start < 0) throw new ResponseStatusException(BAD_REQUEST);
        var reviews = db.query("""
                SELECT p.id, p.status, p.review_revision, p.clearing_reference,
                    CASE WHEN p.status = 'REVIEW_REQUIRED' AND p.reserved_at IS NOT NULL THEN p.amount_minor ELSE 0 END AS held,
                    CASE WHEN p.late_exception_at IS NOT NULL THEN 'LATE_CONFLICTING_EVIDENCE' ELSE t.reason END AS reason,
                    GREATEST(0, EXTRACT(EPOCH FROM clock_timestamp() - COALESCE(p.late_exception_at, t.occurred_at)))::bigint AS age,
                    r.id AS resolution_id, r.executed_at
                FROM payments.payment p
                LEFT JOIN LATERAL (
                    SELECT reason, occurred_at FROM payments.transition
                    WHERE payment_id = p.id AND status = 'REVIEW_REQUIRED'
                    ORDER BY occurred_at DESC, id DESC LIMIT 1
                ) t ON true
                LEFT JOIN payments.review_resolution r ON r.payment_id = p.id
                WHERE p.status = 'REVIEW_REQUIRED' OR p.late_exception_at IS NOT NULL
                ORDER BY COALESCE(p.late_exception_at, t.occurred_at), p.id LIMIT 50 OFFSET ?
                """, (row, index) -> {
            UUID id = row.getObject("id", UUID.class);
            UUID resolution = row.getObject("resolution_id", UUID.class);
            var review = new PaymentReview(id, row.getLong("review_revision"), row.getString("status"),
                    row.getLong("age"), row.getString("reason"), PaymentReview.CurrencyEnum.AUD, row.getLong("held"),
                    reviewEvidence(id)).clearingReference(row.getString("clearing_reference"));
            if (resolution != null) {
                review.resolution(new PaymentResolution(resolution, row.getObject("executed_at") == null
                        ? PaymentResolution.StatusEnum.PENDING : PaymentResolution.StatusEnum.APPLIED));
            }
            return review;
        }, start);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews);
    }

    private List<PaymentReviewEvidence> reviewEvidence(UUID payment) {
        return db.query("""
                SELECT * FROM payments.clearing_inbox WHERE payment_id = ?
                ORDER BY received_at DESC, evidence_id LIMIT 100
                """, (row, index) -> new PaymentReviewEvidence(row.getObject("evidence_id", UUID.class),
                PaymentReviewEvidence.KindEnum.valueOf(row.getString("kind")), row.getLong("amount_minor"),
                PaymentReviewEvidence.CurrencyEnum.AUD, row.getString("reference"),
                row.getObject("received_at", OffsetDateTime.class))
                .businessDate(row.getObject("business_date", LocalDate.class)), payment);
    }

    @Override
    @Transactional
    public ResponseEntity<PaymentResolution> resolvePaymentReview(UUID id, String key, PaymentResolutionRequest request) {
        long operator = requireOperator();
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches() || request.getExpectedRevision() == null
                || request.getExpectedRevision() < 0 || request.getEvidenceId() == null || request.getAction() == null
                || request.getReason() == null || request.getReason().isBlank() || request.getReason().length() > 1000) {
            throw new ResponseStatusException(BAD_REQUEST, "INVALID_INPUT");
        }
        var target = db.query("""
                SELECT status, review_revision, amount_minor, clearing_reference, reserved_at
                FROM payments.payment WHERE id = ? FOR UPDATE
                """, (row, index) -> new ReviewTarget(row.getString("status"), row.getLong("review_revision"),
                row.getLong("amount_minor"), row.getString("clearing_reference"), row.getObject("reserved_at") != null), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        Decision existing = decision(operator, key);
        if (existing != null) return replay(existing, id, request);
        if (target.revision() != request.getExpectedRevision()) {
            throw new ReviewConflict("STALE_REVISION");
        }
        if (!"REVIEW_REQUIRED".equals(target.status()) || !target.reserved()) {
            throw new ReviewConflict("PAYMENT_NOT_RESOLVABLE");
        }
        String kind = request.getAction() == PaymentResolutionRequest.ActionEnum.CONFIRM_SETTLEMENT ? "SETTLED" : "REJECTED";
        boolean verified = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payments.clearing_inbox
                    WHERE evidence_id = ? AND payment_id = ? AND kind = ? AND amount_minor = ?
                        AND (?::varchar IS NULL OR reference = ?)
                        AND (kind <> 'SETTLED' OR business_date IS NOT NULL)
                )
                """, Boolean.class, request.getEvidenceId(), id, kind, target.amount(), target.reference(), target.reference()));
        if (!verified) throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "EVIDENCE_MISMATCH");
        int inserted = db.update("""
                INSERT INTO payments.review_resolution
                    (id, payment_id, operator_id, idempotency_key, expected_revision, evidence_id, action, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), id, operator, key, target.revision(), request.getEvidenceId(),
                request.getAction().name(), request.getReason());
        if (inserted == 1) {
            db.update("UPDATE payments.payment SET review_revision = review_revision + 1 WHERE id = ?", id);
        }
        existing = decision(operator, key);
        if (existing == null) throw new ReviewConflict("RESOLUTION_ALREADY_RECORDED");
        return replay(existing, id, request);
    }

    private Decision decision(long operator, String key) {
        return db.query("""
                SELECT * FROM payments.review_resolution WHERE operator_id = ? AND idempotency_key = ?
                """, (row, index) -> new Decision(row.getObject("id", UUID.class), row.getObject("payment_id", UUID.class),
                row.getObject("evidence_id", UUID.class), row.getLong("expected_revision"), row.getString("action"),
                row.getString("reason"), row.getObject("executed_at") != null), operator, key)
                .stream().findFirst().orElse(null);
    }

    private static ResponseEntity<PaymentResolution> replay(Decision decision, UUID payment, PaymentResolutionRequest request) {
        if (!decision.payment().equals(payment) || !decision.evidence().equals(request.getEvidenceId())
                || decision.revision() != request.getExpectedRevision()
                || !decision.action().equals(request.getAction().name())
                || !Objects.equals(decision.reason(), request.getReason())) {
            throw new ReviewConflict("IDEMPOTENCY_KEY_REUSED");
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(decision.representation());
    }

    private static class ReviewConflict extends ResponseStatusException {
        ReviewConflict(String code) {
            super(CONFLICT, code);
        }
    }

    @ExceptionHandler(ReviewConflict.class)
    ResponseEntity<PaymentReviewError> reviewConflict(ReviewConflict conflict) {
        return ResponseEntity.status(CONFLICT).cacheControl(CacheControl.noStore())
                .body(new PaymentReviewError(conflict.getReason()));
    }

    private long requireOperator() {
        long user = customer(authorization());
        if (!operators.contains(user)) throw new ResponseStatusException(FORBIDDEN);
        return user;
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
