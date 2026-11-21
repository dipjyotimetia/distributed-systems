package com.banking.transactionsservice.service;

import com.banking.transactionsservice.entity.Transaction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class Idempotency {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private Idempotency() {}

    static String key(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader("Idempotency-Key");
        if (header == null || header.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An Idempotency-Key of 1 to 128 characters is required");
        }
        String key = header.trim();
        if (!KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        return key;
    }

    static String fingerprint(Transaction transaction) {
        String material = transaction.getType()
                + "|" + transaction.getFromAccountId()
                + "|" + transaction.getToAccountId()
                + "|" + transaction.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + "|" + (transaction.getDescription() == null ? "" : transaction.getDescription());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
