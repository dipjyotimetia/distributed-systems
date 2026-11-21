package com.banking.transactionsservice.client;

import fraud.v1.Fraud.FraudCheckResponse;
import fraud.v1.Fraud.TransactionRequest;
import fraud.v1.FraudCheckerGrpc;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class FraudServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(FraudServiceClient.class);

    private final FraudCheckerGrpc.FraudCheckerBlockingStub fraudCheckerStub;
    private final boolean failOpen;

    public FraudServiceClient(FraudCheckerGrpc.FraudCheckerBlockingStub fraudCheckerStub,
                              @Value("${fraud.fail-open:true}") boolean failOpen) {
        this.fraudCheckerStub = fraudCheckerStub;
        this.failOpen = failOpen;
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

            FraudCheckResponse response = fraudCheckerStub.withDeadlineAfter(4, TimeUnit.SECONDS).checkTransaction(request);
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
