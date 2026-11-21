package com.banking.payments;

import fraud.v1.Fraud.FraudCheckResponse;
import fraud.v1.Fraud.TransactionRequest;
import fraud.v1.FraudCheckerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ManagedChannel fraud;
    private final URI complianceUrl;
    private final String complianceToken;
    private final String accountUrl;
    private final SecretKeySpec movementKey;
    private final ObjectMapper json;

    private final String fraudSecret;

    public PaymentChecks(@Value("${FRAUD_SERVICE_ADDRESS}") String fraudAddress,
                         @Value("${PAYMENTS_COMPLIANCE_URL}") URI complianceUrl,
                         @Value("${PAYMENTS_COMPLIANCE_TOKEN}") String complianceToken,
                         @Value("${PAYMENTS_ACCOUNTS_URL}") String accountUrl,
                         @Value("${MOVEMENT_SECRET}") String movementSecret,
                         @Value("${FRAUD_CLIENT_SECRET:banking-fraud-client-dev}") String fraudSecret,
                         ObjectMapper json) {
        this.fraud = ManagedChannelBuilder.forTarget(fraudAddress).usePlaintext().build();
        this.complianceUrl = complianceUrl;
        this.complianceToken = complianceToken;
        this.accountUrl = accountUrl.replaceAll("/+$", "");
        this.movementKey = new SecretKeySpec(movementSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.fraudSecret = fraudSecret;
        this.json = json;
    }

    boolean approved(long customer, long account, long cents, String destination) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-fraud-token", Metadata.ASCII_STRING_MARSHALLER), fraudSecret);
        FraudCheckResponse risk = FraudCheckerGrpc.newBlockingStub(fraud)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(4, TimeUnit.SECONDS)
                .checkTransaction(TransactionRequest.newBuilder().setAccountId(Long.toString(account))
                        .setUserId(Long.toString(customer)).setAmount(BigDecimal.valueOf(cents, 2).doubleValue())
                        .setTransactionType("TRANSFER").setMerchantCategory("5812").build());
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
        var request = HttpRequest.newBuilder(URI.create(accountUrl + "/internal/account-movements/outbound-aud/" + id + "/" + action))
                .timeout(TIMEOUT).header("Content-Type", "application/json").header("X-Movement-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"userId":%d,"fromAccountId":%d,"toAccountId":null,"amount":%s}
                        """.formatted(customer, account, amount))).build();
        int status = send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status == 204) return true;
        if (action == Movement.RESERVE && (status == 404 || status == 409 || status == 422)) return false;
        throw new IllegalStateException("Account movement has no successful acknowledgement (HTTP " + status + ")");
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
        http.close();
    }
}
