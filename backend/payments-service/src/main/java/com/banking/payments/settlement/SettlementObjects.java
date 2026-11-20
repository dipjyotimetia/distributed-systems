package com.banking.payments.settlement;

import com.banking.payments.clearing.SettlementFile;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

@Component
@Profile("clearing | reconciliation")
public class SettlementObjects {
    private final S3Client client;
    private final String bucket;

    @org.springframework.beans.factory.annotation.Autowired
    public SettlementObjects(@Value("${SETTLEMENT_BUCKET}") String bucket,
                             @Value("${SETTLEMENT_S3_ENDPOINT:}") String endpoint,
                             @Value("${AWS_REGION:ap-southeast-2}") String region) {
        this(bucket, connect(endpoint, region));
    }

    SettlementObjects(String bucket, S3Client client) {
        this.bucket = bucket;
        this.client = client;
    }

    private static S3Client connect(String endpoint, String region) {
        var builder = S3Client.builder().region(Region.of(region))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15)));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).serviceConfiguration(pathStyleConfiguration());
        }
        return builder.build();
    }

    static S3Configuration pathStyleConfiguration() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build();
    }

    public void put(LocalDate date, byte[] payload) {
        try {
            client.putObject(request -> request.bucket(bucket).key(key(date)).ifNoneMatch("*")
                    .contentType("application/octet-stream"), RequestBody.fromBytes(payload));
        } catch (S3Exception error) {
            if (error.statusCode() != 412) throw error;
            if (get(date).filter(existing -> Arrays.equals(existing, payload)).isEmpty()) {
                throw new IllegalStateException("Existing settlement object differs for " + date, error);
            }
        }
    }

    public Optional<byte[]> get(LocalDate date) {
        try (var stream = client.getObject(request -> request.bucket(bucket).key(key(date)))) {
            byte[] bytes = stream.readNBytes(SettlementFile.MAX_BYTES + 1);
            if (bytes.length > SettlementFile.MAX_BYTES) throw new IllegalStateException("Settlement object exceeds size limit");
            return Optional.of(bytes);
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return Optional.empty();
            throw error;
        } catch (IOException error) {
            throw new IllegalStateException("Could not read settlement evidence", error);
        }
    }

    private static String key(LocalDate date) {
        return "settlement/aud/" + date + ".dat";
    }

    @PreDestroy
    void stop() {
        client.close();
    }
}
