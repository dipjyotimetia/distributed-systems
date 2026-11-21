package com.banking.transactionsservice.client;

import ledger.v1.AccountLedgerGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

@Configuration
@ImportGrpcClients(target = "accounts-ledger", types = AccountLedgerGrpc.AccountLedgerBlockingStub.class)
public class LedgerClientConfig {
}
