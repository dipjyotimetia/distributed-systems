package com.banking.accountsservice.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "ledger.grpc.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerGrpcServer {
    private final Server server;

    public LedgerGrpcServer(LedgerGrpcService service, @Value("${ledger.grpc.port:9092}") int port) throws IOException {
        this.server = ServerBuilder.forPort(port)
                .intercept(new HeaderInterceptor())
                .addService(service)
                .build()
                .start();
    }

    public int port() {
        return server.getPort();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        server.shutdown().awaitTermination(3, TimeUnit.SECONDS);
    }

    private static final class HeaderInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                     ServerCallHandler<ReqT, RespT> next) {
            Context context = Context.current().withValue(LedgerGrpcService.HEADERS, headers);
            return Contexts.interceptCall(context, call, headers, next);
        }
    }
}
