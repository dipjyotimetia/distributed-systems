package com.banking.accountsservice.service;

import com.banking.accountsservice.entity.Account;
import com.banking.accountsservice.repository.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatementExportServiceTest {
    @Test
    void writesOwnedStatementToConfiguredPathStyleEndpoint() throws Exception {
        var repository = mock(AccountRepository.class);
        var account = new Account(7L, "100000000012", new BigDecimal("40.00"), "SAVINGS");
        account.setId(12L);
        when(repository.findByIdAndUserId(12L, 7L)).thenReturn(Optional.of(account));

        var path = new AtomicReference<String>();
        var object = new AtomicReference<JsonNode>();
        var fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/statements-test/", exchange -> {
            try (exchange) {
                if (!"PUT".equals(exchange.getRequestMethod()) ||
                        !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                path.set(exchange.getRequestURI().getPath());
                object.set(new ObjectMapper().readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        fixture.start();
        try {
            var service = new StatementExportService();
            ReflectionTestUtils.setField(service, "accountRepository", repository);
            service.configure("statements-test", "us-east-1", "http://127.0.0.1:" + fixture.getAddress().getPort(),
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic-access", "synthetic-secret")));

            var key = service.exportStatement(12L, 7L);

            assertThat(key).startsWith("statements/account-12/").endsWith(".json");
            assertThat(path.get()).isEqualTo("/statements-test/" + key);
            assertThat(object.get().get("accountId").asLong()).isEqualTo(12);
            assertThat(object.get().get("userId").asLong()).isEqualTo(7);
            assertThat(object.get().get("balance").decimalValue()).isEqualByComparingTo("40.00");
        } finally {
            fixture.stop(0);
        }
    }
}
