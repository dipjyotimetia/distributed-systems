package com.banking.payments;

import com.banking.events.PaymentEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SchemaRegistryAvroEncoder {
    private final boolean enabled;
    private final String registryUrl;
    private final String topic;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Integer> schemaIds = new ConcurrentHashMap<>();

    public SchemaRegistryAvroEncoder(
            @Value("${SCHEMA_REGISTRY_URL:http://banking-kafka:8081}") String registryUrl,
            @Value("${KAFKA_VALUE_FORMAT:avro}") String format,
            @Value("${PAYMENTS_KAFKA_TOPIC:payment-events.v1}") String topic) {
        this.registryUrl = registryUrl == null ? "" : registryUrl.replaceAll("/$", "");
        this.enabled = !"json".equalsIgnoreCase(format) && !this.registryUrl.isBlank();
        this.topic = topic;
    }

    public byte[] encode(byte[] jsonBody) {
        if (!enabled) {
            return jsonBody;
        }
        try {
            int schemaId = schemaIds.computeIfAbsent(topic + "-value", this::register);
            return confluentWire(schemaId, toAvroBytes(jsonBody));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Avro encode failed for payment event", failure);
        }
    }

    private int register(String subject) {
        try {
            String body = json.writeValueAsString(Map.of("schema", PaymentEvent.getClassSchema().toString()));
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

    private byte[] toAvroBytes(byte[] jsonBody) throws IOException {
        JsonNode node = json.readTree(jsonBody);
        PaymentEvent event = PaymentEvent.newBuilder()
                .setVersion(node.path("version").asInt())
                .setEventId(node.path("eventId").asText())
                .setPaymentId(node.path("paymentId").asText())
                .setCustomerId(node.path("customerId").asLong())
                .setAmountMinor(node.path("amountMinor").asLong())
                .setCurrency(node.path("currency").asText())
                .setStatus(node.path("status").asText())
                .setClearingReference(textOrNull(node, "clearingReference"))
                .setSettlementBusinessDate(textOrNull(node, "settlementBusinessDate"))
                .setOccurredAt(node.path("occurredAt").asText())
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<>(PaymentEvent.getClassSchema()).write(event, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    static byte[] confluentWire(int schemaId, byte[] avroPayload) {
        ByteBuffer buffer = ByteBuffer.allocate(5 + avroPayload.length);
        buffer.put((byte) 0);
        buffer.putInt(schemaId);
        buffer.put(avroPayload);
        return buffer.array();
    }
}
