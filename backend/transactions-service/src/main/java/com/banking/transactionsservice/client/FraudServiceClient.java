package com.banking.transactionsservice.client;

import fraud.v1.Fraud.FraudCheckResponse;
import fraud.v1.Fraud.TransactionRequest;
import fraud.v1.FraudCheckerGrpc;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FraudServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(FraudServiceClient.class);

    private static final Metadata.Key<String> FRAUD_TOKEN = Metadata.Key.of("x-fraud-token", Metadata.ASCII_STRING_MARSHALLER);

    private final FraudCheckerGrpc.FraudCheckerBlockingStub fraudCheckerStub;
    private final boolean failOpen;
    private final String clientSecret;

    public FraudServiceClient(FraudCheckerGrpc.FraudCheckerBlockingStub fraudCheckerStub,
                              @Value("${fraud.fail-open:false}") boolean failOpen,
                              @Value("${fraud.client-secret:}") String clientSecret) {
        this.fraudCheckerStub = fraudCheckerStub;
        this.failOpen = failOpen;
        this.clientSecret = clientSecret;
    }

    public FraudCheckResponse checkTransaction(String accountId, String userId, double amount, String transactionType, String merchantCategory) {
        try {
            TransactionRequest request = TransactionRequest.newBuilder()
                    .setAccountId(accountId)
                    .setUserId(userId)
                    .setAmount(amount)
                    .setTransactionType(transactionType)
                    .setMerchantCategory(merchantCategory == null ? "" : merchantCategory)
                    .build();

            if (clientSecret == null || clientSecret.isBlank()) {
                return unavailable(accountId, userId, new IllegalStateException("fraud client secret is not configured"));
            }
            Metadata headers = new Metadata();
            headers.put(FRAUD_TOKEN, clientSecret);
            FraudCheckResponse response = fraudCheckerStub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .checkTransaction(request);
            logger.info("Fraud check for account={} user={} type={} amount={}: approved={} risk={} reason={}",
                    accountId, userId, transactionType, amount,
                    response.getApproved(), response.getRiskScore(), response.getReason());
            return response;
        } catch (Exception e) {
            return unavailable(accountId, userId, e);
        }
    }

    FraudCheckResponse unavailable(String accountId, String userId, Exception failure) {
        String decision = failOpen ? "fail-open" : "fail-closed";
        Span.current().setAttribute("fraud.decision", decision);
        logger.warn("Fraud service unavailable for account={} user={}, {}: {}",
                accountId, userId, decision, failure.getMessage());
        return FraudCheckResponse.newBuilder()
                .setApproved(failOpen)
                .setRiskScore(0.0)
                .setReason("fraud-service unavailable, " + decision)
                .build();
    }
}
