package com.banking.transactionsservice.grpc;

import com.banking.transactionsservice.security.JwtTokenUtil;
import com.banking.transactionsservice.service.TransactionService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import transactions.v1.GetTransactionRequest;
import transactions.v1.TransactionQueryGrpc;
import transactions.v1.TransactionView;

@Component
public class TransactionQueryGrpcService extends TransactionQueryGrpc.TransactionQueryImplBase {
    private final TransactionService transactions;
    private final JwtTokenUtil jwt;

    public TransactionQueryGrpcService(TransactionService transactions, JwtTokenUtil jwt) {
        this.transactions = transactions;
        this.jwt = jwt;
    }

    @Override
    public void getTransaction(GetTransactionRequest request, StreamObserver<TransactionView> response) {
        String authorization = TransactionQueryGrpcServer.authorization();
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || !jwt.validateToken(authorization.substring(7))) {
            response.onError(Status.UNAUTHENTICATED.withDescription("Sign in to continue").asRuntimeException());
            return;
        }
        try {
            var transaction = transactions.getTransaction(request.getId(), jwt.getUserIdFromToken(authorization.substring(7)));
            response.onNext(TransactionView.newBuilder()
                    .setId(transaction.getId())
                    .setUserId(transaction.getUserId())
                    .setType(transaction.getType().name())
                    .setStatus(transaction.getStatus().name())
                    .build());
            response.onCompleted();
        } catch (ResponseStatusException missing) {
            response.onError(Status.NOT_FOUND.withDescription(missing.getReason()).asRuntimeException());
        }
    }
}
