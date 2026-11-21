package com.banking.transactionsservice.grpc;

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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Lazy(false)
public class TransactionQueryGrpcServer {
    static final Context.Key<Metadata> HEADERS = Context.key("transaction-query-headers");
    private final Server server;

    public TransactionQueryGrpcServer(TransactionQueryGrpcService service,
                                      @Value("${transactions.grpc.port:9095}") int port) throws IOException {
        this.server = ServerBuilder.forPort(port)
                .intercept(new HeaderInterceptor())
                .addService(service)
                .build()
                .start();
    }

    static String authorization() {
        Metadata metadata = HEADERS.get();
        if (metadata == null) {
            return null;
        }
        return metadata.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
    }

    @PreDestroy
    void stop() throws InterruptedException {
        server.shutdown().awaitTermination(3, TimeUnit.SECONDS);
    }

    private static final class HeaderInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                     ServerCallHandler<ReqT, RespT> next) {
            return Contexts.interceptCall(Context.current().withValue(HEADERS, headers), call, headers, next);
        }
    }
}
