package com.banking.transactionsservice;

import com.banking.transactionsservice.client.FraudServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TransactionsServiceApplicationTests {

    // Prevent Spring from wiring the real gRPC client and Kafka producer during context load
    @MockitoBean
    private FraudServiceClient fraudServiceClient;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void contextLoads() {
    }
}