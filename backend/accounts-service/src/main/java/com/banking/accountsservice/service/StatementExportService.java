package com.banking.accountsservice.service;

import com.banking.accountsservice.entity.Account;
import com.banking.accountsservice.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class StatementExportService {

    private static final Logger logger = LoggerFactory.getLogger(StatementExportService.class);

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private S3Client s3Client;
    private String bucketName;
    private boolean configured;
    private final ObjectMapper objectMapper;

    public StatementExportService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    void init() {
        configure(System.getenv("AWS_S3_BUCKET"), System.getenv("AWS_REGION"),
                System.getenv("AWS_S3_ENDPOINT_URL"), DefaultCredentialsProvider.create());
    }

    void configure(String bucket, String regionName, String endpoint, AwsCredentialsProvider credentials) {
        if (bucket == null || bucket.isBlank()) {
            logger.warn("AWS_S3_BUCKET not set. Statement export disabled");
            return;
        }

        Region region = regionName == null || regionName.isBlank() ? Region.US_EAST_1 : Region.of(regionName);
        var builder = S3Client.builder().region(region).credentialsProvider(credentials);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build());
        }
        this.s3Client = builder.build();
        this.bucketName = bucket;
        this.configured = true;
        logger.info("Statement export configured: bucket={}, region={}", bucketName, region);
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Builds the statement document for an account. Does not touch S3, so it is
     * safe (and egress-free) to call even when no object store is configured.
     */
    public Map<String, Object> generateStatement(Long accountId, Long userId) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new RuntimeException("Account not found or access denied"));

        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("accountId", account.getId());
        statement.put("accountNumber", account.getAccountNumber());
        statement.put("accountType", account.getAccountType());
        statement.put("balance", account.getBalance());
        statement.put("exportedAt", LocalDateTime.now().toString());
        statement.put("userId", userId);
        return statement;
    }

    public record Period(OffsetDateTime start, OffsetDateTime end) {}
    public record PostedEntry(long id, UUID operationId, String amount, OffsetDateTime postedAt, String source) {}
    public record PeriodDocument(int schemaVersion, long accountId, Period period, String currency,
                                 String openingBalance, List<PostedEntry> entries, String closingBalance,
                                 OffsetDateTime generatedAt) {}

    public OffsetDateTime coverageStart() {
        return jdbc.queryForObject("SELECT starts_at FROM accounts_service.posting_coverage",
                OffsetDateTime.class);
    }

    @Transactional
    public PeriodDocument generatePeriod(long accountId, long userId, OffsetDateTime start, OffsetDateTime end) {
        // Account posting writers hold the same lock until the balance and entries commit.
        Account account = accountRepository.findOwnedForUpdate(accountId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        BigDecimal sinceOpening = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM accounts_service.account_postings
                WHERE account_id = ? AND posted_at >= ?
                """, BigDecimal.class, accountId, start);
        BigDecimal opening = account.getBalance().subtract(sinceOpening);
        var entries = jdbc.query("""
                SELECT id, operation_id, amount, posted_at, source FROM accounts_service.account_postings
                WHERE account_id = ? AND posted_at >= ? AND posted_at < ?
                ORDER BY posted_at, id
                """, (row, index) -> new PostedEntry(row.getLong("id"), row.getObject("operation_id", UUID.class),
                row.getBigDecimal("amount").toPlainString(), row.getObject("posted_at", OffsetDateTime.class),
                row.getString("source")), accountId, start, end);
        BigDecimal closing = entries.stream().map(entry -> new BigDecimal(entry.amount())).reduce(opening, BigDecimal::add);
        return new PeriodDocument(2, accountId, new Period(start, end), account.getCurrency(),
                opening.toPlainString(), entries, closing.toPlainString(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public String exportStatement(Long accountId, Long userId) {
        Map<String, Object> statement = generateStatement(accountId, userId);

        String timestamp = Instant.now().toString().replace(":", "-");
        String key = "statements/account-" + accountId + "/" + timestamp + ".json";

        try {
            byte[] payload = objectMapper.writeValueAsBytes(statement);

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/json")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(payload));
            logger.info("Exported statement to s3://{}/{}", bucketName, key);
            return key;
        } catch (Exception e) {
            logger.error("Failed to export statement for account {}", accountId, e);
            throw new RuntimeException("Statement export failed: " + e.getMessage(), e);
        }
    }
}
