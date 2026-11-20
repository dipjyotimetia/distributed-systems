package com.banking.payments.settlement;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementObjectsTest {
    @Test
    void conditionalUploadRetainsEvidenceAcrossRetries() throws Exception {
        var stored = new AtomicReference<byte[]>();
        var endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/settlements/settlement/aud/2026-11-20.dat", request -> {
            try (request) {
                byte[] body;
                int status;
                if ("PUT".equals(request.getRequestMethod())) {
                    byte[] proposed = request.getRequestBody().readAllBytes();
                    if (!"*".equals(request.getRequestHeaders().getFirst("If-None-Match"))) {
                        status = 400;
                        body = "<Error><Code>InvalidRequest</Code></Error>".getBytes();
                    } else if (stored.compareAndSet(null, proposed)) {
                        status = 200;
                        body = new byte[0];
                    } else {
                        status = 412;
                        body = "<Error><Code>PreconditionFailed</Code></Error>".getBytes();
                    }
                } else if (stored.get() == null) {
                    status = 404;
                    body = "<Error><Code>NoSuchKey</Code></Error>".getBytes();
                } else {
                    status = 200;
                    body = stored.get();
                }
                request.sendResponseHeaders(status, body.length);
                request.getResponseBody().write(body);
            }
        });
        endpoint.start();
        try (var s3 = S3Client.builder().region(Region.AP_SOUTHEAST_2).serviceConfiguration(SettlementObjects.pathStyleConfiguration())
                .endpointOverride(URI.create("http://127.0.0.1:" + endpoint.getAddress().getPort()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build()) {
            var objects = new SettlementObjects("settlements", s3);
            LocalDate date = LocalDate.of(2026, 11, 20);
            byte[] original = {1, 2, 3};
            assertTrue(objects.get(date).isEmpty());
            objects.put(date, original);
            assertArrayEquals(original, stored.get());
            objects.put(date, original);
            assertThrows(IllegalStateException.class, () -> objects.put(date, new byte[]{4, 5}));
            assertArrayEquals(original, objects.get(date).orElseThrow());
        } finally {
            endpoint.stop(0);
        }
    }
}
