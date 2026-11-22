package com.banking.transactionsservice.event;

import com.banking.events.MovementLifecycleEvent;
import com.banking.events.MovementMoney;
import com.banking.events.TransactionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.util.ClassSecurityValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encodes JSON outbox payloads as Confluent-wire-format Avro when enabled.
 * Uses SpecificRecord types generated from contracts/avro.
 */
@Component
public class SchemaRegistryAvroEncoder {
    private static final String TRUSTED_EVENT_PACKAGE = "com.banking.events";
    private static final String SERIALIZABLE_PACKAGES_PROP = "org.apache.avro.SERIALIZABLE_PACKAGES";

    static {
        trustGeneratedEventClasses();
    }

    private final boolean enabled;
    private final String registryUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Integer> schemaIds = new ConcurrentHashMap<>();

    /**
     * Avro 1.12.2+ refuses to load SpecificRecord classes unless their package is trusted.
     * Trust our generated contracts package via system property and the ClassSecurityValidator API.
     */
    static void trustGeneratedEventClasses() {
        String existing = System.getProperty(SERIALIZABLE_PACKAGES_PROP, "");
        if (existing.isBlank()) {
            System.setProperty(SERIALIZABLE_PACKAGES_PROP, TRUSTED_EVENT_PACKAGE);
        } else if (!csvContainsPackage(existing, TRUSTED_EVENT_PACKAGE)) {
            System.setProperty(SERIALIZABLE_PACKAGES_PROP, existing + "," + TRUSTED_EVENT_PACKAGE);
        }
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(
                ClassSecurityValidator.getGlobal(),
                type -> {
                    String name = type.getName();
                    return name.equals(TRUSTED_EVENT_PACKAGE) || name.startsWith(TRUSTED_EVENT_PACKAGE + ".");
                }));
    }

    private static boolean csvContainsPackage(String csv, String pkg) {
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.equals("*") || trimmed.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    public SchemaRegistryAvroEncoder(
            @Value("${SCHEMA_REGISTRY_URL:http://banking-kafka:8081}") String registryUrl,
            @Value("${KAFKA_VALUE_FORMAT:avro}") String format) {
        this.registryUrl = registryUrl == null ? "" : registryUrl.replaceAll("/$", "");
        this.enabled = !"json".equalsIgnoreCase(format) && !this.registryUrl.isBlank();
    }

    public boolean enabled() {
        return enabled;
    }

    public byte[] encode(String topic, String payload) {
        if (!enabled) {
            return payload.getBytes(StandardCharsets.UTF_8);
        }
        try {
            SpecificRecord record = isMovementTopic(topic) ? toMovement(payload) : toTransaction(payload);
            Schema schema = record.getSchema();
            int schemaId = schemaIds.computeIfAbsent(subject(topic), sub -> register(sub, schema));
            return confluentWire(schemaId, toAvroBytes(record, schema));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Avro encode failed for topic " + topic, failure);
        }
    }

    private static boolean isMovementTopic(String topic) {
        return topic != null && topic.contains("movement-lifecycle");
    }

    private String subject(String topic) {
        return (topic == null || topic.isBlank() ? "transaction-events" : topic) + "-value";
    }

    private int register(String subject, Schema schema) {
        try {
            String body = json.writeValueAsString(Map.of("schema", schema.toString()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(registryUrl + "/subjects/" + subject + "/versions"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("schema registry HTTP " + response.statusCode() + ": " + response.body());
            }
            return json.readTree(response.body()).path("id").asInt();
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("register schema for " + subject, failure);
        }
    }

    private TransactionEvent toTransaction(String payload) throws IOException {
        JsonNode node = json.readTree(payload);
        return TransactionEvent.newBuilder()
                .setTransactionId(node.path("transaction_id").asLong())
                .setAccountId(longOrNull(node, "account_id"))
                .setUserId(node.path("user_id").asLong())
                .setAmount(node.path("amount").asDouble())
                .setTransactionType(node.path("transaction_type").asText())
                .setStatus(node.path("status").asText())
                .setTimestamp(node.path("timestamp").asText())
                .build();
    }

    private MovementLifecycleEvent toMovement(String payload) throws IOException {
        JsonNode node = json.readTree(payload);
        JsonNode money = node.path("money");
        return MovementLifecycleEvent.newBuilder()
                .setSchemaVersion(node.path("schemaVersion").asInt())
                .setEventId(node.path("eventId").asText())
                .setMovementId(node.path("movementId").asText())
                .setType(node.path("type").asText())
                .setFromAccountId(longOrNull(node, "fromAccountId"))
                .setToAccountId(longOrNull(node, "toAccountId"))
                .setMoney(MovementMoney.newBuilder()
                        .setCurrency(money.path("currency").asText())
                        .setAmount(money.path("amount").asText())
                        .build())
                .setStatus(node.path("status").asText())
                .setOccurredAt(node.path("occurredAt").asText())
                .build();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asLong();
    }

    private static byte[] toAvroBytes(SpecificRecord record, Schema schema) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<SpecificRecord>(schema).write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    static byte[] confluentWire(int schemaId, byte[] avroPayload) {
        ByteBuffer buffer = ByteBuffer.allocate(5 + avroPayload.length);
        buffer.put((byte) 0);
        buffer.putInt(schemaId);
        buffer.put(avroPayload);
        return buffer.array();
    }
}
