package com.banking.transactionsservice.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Builds sasl.jaas.config from KAFKA_SASL_USERNAME / KAFKA_SASL_PASSWORD.
 * Kubernetes does not expand $(VAR) in env values, so the overlay must not
 * embed credentials inside SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.properties.security.protocol", havingValue = "SASL_PLAINTEXT")
public class KafkaSaslConfig {

    @Bean
    DefaultKafkaProducerFactoryCustomizer kafkaSaslJaasFromEnv(
            @Value("${KAFKA_SASL_USERNAME:}") String username,
            @Value("${KAFKA_SASL_PASSWORD:}") String password) {
        return factory -> {
            if (username == null || username.isBlank()) {
                throw new IllegalStateException(
                        "KAFKA_SASL_USERNAME is required when spring.kafka.properties.security.protocol=SASL_PLAINTEXT");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "KAFKA_SASL_PASSWORD is required when spring.kafka.properties.security.protocol=SASL_PLAINTEXT");
            }
            factory.updateConfigs(Map.of(
                    CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT",
                    SaslConfigs.SASL_MECHANISM, "PLAIN",
                    SaslConfigs.SASL_JAAS_CONFIG, plainJaas(username, password)
            ));
        };
    }

    static String plainJaas(String username, String password) {
        return "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                + escapeJaas(username) + "\" password=\"" + escapeJaas(password) + "\";";
    }

    private static String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
