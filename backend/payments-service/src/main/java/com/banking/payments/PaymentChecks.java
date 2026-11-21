package com.banking.payments;

import fraud.v1.Fraud.FraudCheckResponse;
import fraud.v1.Fraud.TransactionRequest;
import fraud.v1.FraudCheckerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import ledger.v1.AccountLedgerGrpc;
import ledger.v1.MovementRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Profile("payment-worker")
public class PaymentChecks {
    enum Movement { RESERVE, COMMIT, COMPENSATE }
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final Metadata.Key<String> SIGNATURE = Metadata.Key.of("x-movement-signature", Metadata.ASCII_STRING_MARSHALLER);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ManagedChannel fraud;
    private final ManagedChannel ledger;
    private final URI complianceUrl;
    private final String complianceToken;
    private final SecretKeySpec movementKey;
    private final ObjectMapper json;

    public PaymentChecks(@Value("${FRAUD_SERVICE_ADDRESS}") String fraudAddress,
                         @Value("${PAYMENTS_COMPLIANCE_URL}") URI complianceUrl,
                         @Value("${PAYMENTS_COMPLIANCE_TOKEN}") String complianceToken,
                         @Value("${ACCOUNTS_GRPC_ADDRESS:accounts-service:9092}") String accountsGrpc,
                         @Value("${MOVEMENT_SECRET}") String movementSecret, ObjectMapper json) {
        this.fraud = ManagedChannelBuilder.forTarget(fraudAddress).usePlaintext().build();
        this.ledger = ManagedChannelBuilder.forTarget(accountsGrpc).usePlaintext().build();
        this.complianceUrl = complianceUrl;
        this.complianceToken = complianceToken;
        this.movementKey = new SecretKeySpec(movementSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.json = json;
    }

    boolean approved(long customer, long account, long cents, String destination) {
        FraudCheckResponse risk = FraudCheckerGrpc.newBlockingStub(fraud).withDeadlineAfter(4, TimeUnit.SECONDS)
                .checkTransaction(TransactionRequest.newBuilder().setAccountId(Long.toString(account))
                        .setUserId(Long.toString(customer)).setAmount(BigDecimal.valueOf(cents, 2).doubleValue())
                        .setTransactionType("TRANSFER").setMerchantCategory("5812").build());
        if ("external-verification-unavailable".equals(risk.getReason())) {
            throw new IllegalStateException("Fraud screening unavailable");
        }
        if (!risk.getApproved()) return false;

        var request = HttpRequest.newBuilder(complianceUrl).timeout(TIMEOUT)
                .header("Authorization", "Token " + complianceToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"search_term":"%s","search_type":"individual","fuzziness":0.6}
                        """.formatted(destination))).build();
        var response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Compliance screening unavailable");
        var data = json.readTree(response.body()).path("content").path("data");
        if (!data.path("total_matches").isIntegralNumber() || !data.path("risk_level").isString()) {
            throw new IllegalStateException("Incomplete compliance decision");
        }
        return data.path("total_matches").intValue() == 0 && "low".equalsIgnoreCase(data.path("risk_level").stringValue());
    }

    boolean move(UUID id, Movement action, long customer, long account, long cents) {
        String amount = BigDecimal.valueOf(cents, 2).toPlainString();
        String canonical = "outbound-aud-movement:v1:" + id + ":" + action + ":" + customer + ":" + account
                + ":null:" + amount;
        String signature;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(movementKey);
            signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot sign account movement", failure);
        }
        Metadata headers = new Metadata();
        headers.put(SIGNATURE, signature);
        var request = MovementRequest.newBuilder().setOperationId(id.toString()).setAction(action.name())
                .setUserId(customer).setFromAccountId(account).setAmount(amount).build();
        try {
            AccountLedgerGrpc.newBlockingStub(ledger).withDeadlineAfter(4, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .applyOutboundAud(request);
            return true;
        } catch (StatusRuntimeException failure) {
            Status.Code code = failure.getStatus().getCode();
            if (action == Movement.RESERVE && (code == Status.Code.NOT_FOUND || code == Status.Code.ABORTED
                    || code == Status.Code.FAILED_PRECONDITION)) {
                return false;
            }
            throw new IllegalStateException("Account movement has no successful acknowledgement (" + code + ")");
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) {
        try {
            return http.send(request, body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Service call interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Service call failed", failure);
        }
    }

    @PreDestroy
    void close() {
        fraud.shutdownNow();
        ledger.shutdownNow();
        http.close();
    }
}
