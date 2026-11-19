package com.banking.transactionsservice;

import com.banking.transactions.grpc.FraudCheckerGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ImportGrpcClients(target = "fraud-service", types = FraudCheckerGrpc.FraudCheckerBlockingStub.class)
@EnableScheduling
@EnableTransactionManagement
public class TransactionsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionsServiceApplication.class, args);
    }
}