package com.banking.accountsservice.grpc;

import com.banking.accountsservice.security.JwtTokenUtil;
import com.banking.accountsservice.service.AccountService;
import com.banking.accountsservice.service.MovementReservations;
import com.banking.accountsservice.service.MovementReservations.Action;
import com.banking.accountsservice.service.MovementReservations.Movement;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import ledger.v1.AccountLedgerGrpc;
import ledger.v1.ExactMovementCommand;
import ledger.v1.ExactMovementOutcome;
import ledger.v1.MovementScope;
import ledger.v1.GetAccountRequest;
import ledger.v1.GetAccountResponse;
import ledger.v1.MovementRequest;
import ledger.v1.MovementResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class LedgerGrpcService extends AccountLedgerGrpc.AccountLedgerImplBase {
    static final Metadata.Key<String> SIGNATURE = Metadata.Key.of("x-movement-signature", Metadata.ASCII_STRING_MARSHALLER);
    static final Context.Key<Metadata> HEADERS = Context.key("ledger-headers");

    private final MovementReservations reservations;
    private final AccountService accounts;
    private final JwtTokenUtil jwt;
    private final String secret;

    @Value("${movement.exact-internal-secret:${movement.secret:${jwt.secret}}}")
    private String exactInternalSecret;

    @Value("${movement.exact-outbound-secret:${movement.secret:${jwt.secret}}}")
    private String exactOutboundSecret;

    public LedgerGrpcService(MovementReservations reservations, AccountService accounts, JwtTokenUtil jwt,
                             @Value("${movement.secret:${jwt.secret}}") String secret) {
        this.reservations = reservations;
        this.accounts = accounts;
        this.jwt = jwt;
        this.secret = secret;
    }

    @Override
    public void applyMovement(MovementRequest request, StreamObserver<MovementResult> response) {
        apply(request, response, "account-movement:v1:", false);
    }

    @Override
    public void applyOutboundAud(MovementRequest request, StreamObserver<MovementResult> response) {
        apply(request, response, "outbound-aud-movement:v1:", true);
    }

    @Override
    public void getAccount(GetAccountRequest request, StreamObserver<GetAccountResponse> response) {
        String authorization = header("authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || !jwt.validateToken(authorization.substring(7))) {
            response.onError(Status.UNAUTHENTICATED.withDescription("Sign in to continue").asRuntimeException());
            return;
        }
        try {
            var account = accounts.getAccountById(request.getAccountId(), jwt.getUserIdFromToken(authorization.substring(7)));
            response.onNext(GetAccountResponse.newBuilder()
                    .setAccountId(account.getId())
                    .setUserId(jwt.getUserIdFromToken(authorization.substring(7)))
                    .setAccountNumber(account.getAccountNumber())
                    .build());
            response.onCompleted();
        } catch (RuntimeException notOwned) {
            response.onError(Status.NOT_FOUND.withDescription("Account not found").asRuntimeException());
        }
    }

    @Override
    public void reserve(ExactMovementCommand command, StreamObserver<ExactMovementOutcome> response) {
        exact(command, response, Action.RESERVE, ExactMovementOutcome.Status.STATUS_RESERVED);
    }

    @Override
    public void post(ExactMovementCommand command, StreamObserver<ExactMovementOutcome> response) {
        exact(command, response, Action.COMMIT, ExactMovementOutcome.Status.STATUS_POSTED);
    }

    @Override
    public void release(ExactMovementCommand command, StreamObserver<ExactMovementOutcome> response) {
        exact(command, response, Action.COMPENSATE, ExactMovementOutcome.Status.STATUS_RELEASED);
    }

    private void exact(ExactMovementCommand command, StreamObserver<ExactMovementOutcome> response,
                       Action action, ExactMovementOutcome.Status outcome) {
        try {
            UUID operation = UUID.fromString(command.getOperationId());
            String currency = command.getCurrency();
            boolean outbound = command.getScope() == MovementScope.MOVEMENT_SCOPE_OUTBOUND;
            if ((!outbound && command.getScope() != MovementScope.MOVEMENT_SCOPE_INTERNAL)
                    || (outbound && (!"AUD".equals(currency) || !command.hasFromAccountId() || command.hasToAccountId()))
                    || !currency.matches("[A-Z]{3}")
                    || !command.getAmount().matches("(0|[1-9][0-9]{0,16})([.][0-9]{1,2})?")) {
                throw new IllegalArgumentException("Invalid exact movement");
            }
            Movement movement = new Movement(command.getUserId(),
                    command.hasFromAccountId() ? command.getFromAccountId() : null,
                    command.hasToAccountId() ? command.getToAccountId() : null, new BigDecimal(command.getAmount()));
            String scope = outbound ? "exact-outbound-movement:v1:" : "exact-internal-movement:v1:";
            if (!signed(scope + currency + ":", operation, action, movement,
                    header("x-movement-signature"), outbound ? exactOutboundSecret : exactInternalSecret)) {
                response.onError(Status.PERMISSION_DENIED.asRuntimeException());
                return;
            }
            reservations.applyExact(operation, action, movement, currency, outbound);
            response.onNext(ExactMovementOutcome.newBuilder().setStatus(outcome).build());
            response.onCompleted();
        } catch (ResponseStatusException failure) {
            response.onError(grpc(failure.getStatusCode()).withDescription(failure.getReason()).asRuntimeException());
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            response.onError(Status.INVALID_ARGUMENT.withDescription("Invalid exact movement").asRuntimeException());
        }
    }

    private void apply(MovementRequest request, StreamObserver<MovementResult> response, String scope, boolean outbound) {
        try {
            UUID operation = UUID.fromString(request.getOperationId());
            Action action = Action.valueOf(request.getAction());
            Movement movement = new Movement(request.getUserId(),
                    request.hasFromAccountId() ? request.getFromAccountId() : null,
                    request.hasToAccountId() ? request.getToAccountId() : null,
                    new BigDecimal(request.getAmount()));
            if (!signed(scope, operation, action, movement, header("x-movement-signature"), secret)) {
                response.onError(Status.PERMISSION_DENIED.asRuntimeException());
                return;
            }
            if (outbound) {
                reservations.applyOutbound(operation, action, movement);
            } else {
                reservations.apply(operation, action, movement);
            }
            response.onNext(MovementResult.getDefaultInstance());
            response.onCompleted();
        } catch (ResponseStatusException failure) {
            response.onError(grpc(failure.getStatusCode()).withDescription(failure.getReason()).asRuntimeException());
        } catch (IllegalArgumentException invalid) {
            response.onError(Status.INVALID_ARGUMENT.withDescription("Invalid movement").asRuntimeException());
        }
    }

    private boolean signed(String scope, UUID operation, Action action, Movement movement, String signature, String key) {
        if (signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String data = scope + operation + ":" + action + ":" + movement.userId()
                    + ":" + movement.fromAccountId() + ":" + movement.toAccountId() + ":" + movement.amount().toPlainString();
            byte[] expected = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature));
        } catch (IllegalArgumentException | GeneralSecurityException invalid) {
            return false;
        }
    }

    private static String header(String name) {
        Metadata metadata = HEADERS.get();
        if (metadata == null) {
            return null;
        }
        return metadata.get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
    }

    private static Status grpc(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> Status.INVALID_ARGUMENT;
            case 401 -> Status.UNAUTHENTICATED;
            case 403 -> Status.PERMISSION_DENIED;
            case 404 -> Status.NOT_FOUND;
            case 409 -> Status.ABORTED;
            case 422 -> Status.FAILED_PRECONDITION;
            default -> Status.INTERNAL;
        };
    }
}
