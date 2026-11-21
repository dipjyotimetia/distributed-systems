package com.banking.transactionsservice.client;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import jakarta.servlet.http.HttpServletRequest;
import ledger.v1.AccountLedgerGrpc;
import ledger.v1.GetAccountRequest;
import ledger.v1.MovementRequest;
import com.banking.transactionsservice.service.InsufficientFundsException;
import com.banking.transactionsservice.service.MovementCoordinator.Action;
import com.banking.transactionsservice.service.MovementCoordinator.Movement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class AccountsServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(AccountsServiceClient.class);
    private static final Metadata.Key<String> SIGNATURE = Metadata.Key.of("x-movement-signature", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Value("${movement.secret:${jwt.secret}}")
    private String movementSecret;

    private final AccountLedgerGrpc.AccountLedgerBlockingStub ledger;

    public AccountsServiceClient(AccountLedgerGrpc.AccountLedgerBlockingStub ledger) {
        this.ledger = ledger;
    }

    public void applyMovement(UUID operation, Action action, Movement movement) {
        Metadata headers = new Metadata();
        headers.put(SIGNATURE, sign("account-movement:v1:", operation, action, movement));
        MovementRequest.Builder request = MovementRequest.newBuilder()
                .setOperationId(operation.toString())
                .setAction(action.name())
                .setUserId(movement.userId())
                .setAmount(movement.amount().toPlainString());
        if (movement.fromAccountId() != null) {
            request.setFromAccountId(movement.fromAccountId());
        }
        if (movement.toAccountId() != null) {
            request.setToAccountId(movement.toAccountId());
        }
        try {
            ledger.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).applyMovement(request.build());
        } catch (StatusRuntimeException e) {
            if (action == Action.RESERVE && e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                throw new InsufficientFundsException("Insufficient balance for movement");
            }
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account movement rejected");
            }
            if (e.getStatus().getCode() == Status.Code.ABORTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Account movement rejected");
            }
            throw e;
        }
    }

    public boolean validateAccountOwnership(Long accountId, HttpServletRequest request) {
        try {
            Metadata headers = new Metadata();
            headers.put(AUTHORIZATION, request.getHeader("Authorization"));
            ledger.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .getAccount(GetAccountRequest.newBuilder().setAccountId(accountId).build());
            return true;
        } catch (StatusRuntimeException e) {
            logger.error("Error validating account ownership for account {}: {}", accountId, e.getMessage());
            return false;
        }
    }

    private String sign(String scope, UUID operation, Action action, Movement movement) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(movementSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String data = scope + operation + ":" + action + ":" + movement.userId()
                    + ":" + movement.fromAccountId() + ":" + movement.toAccountId() + ":" + movement.amount().toPlainString();
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign movement request", e);
        }
    }
}
