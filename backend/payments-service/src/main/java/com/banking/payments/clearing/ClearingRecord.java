package com.banking.payments.clearing;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Version 01 of the AUD clearing interchange. Offsets count ASCII bytes.
 * The last field contains the destination for an instruction and the
 * immutable clearing reference for a reply.
 */
public record ClearingRecord(Kind kind, UUID paymentId, long amountMinor, String identifier) {
    public static final int LENGTH = 66;
    public static final long MAX_AMOUNT_MINOR = 999_999_999_999L;
    private static final Pattern PAYMENT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{12}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z0-9]{12}");

    public enum Kind {
        INSTRUCTION('I'), ACCEPTED('A'), SETTLED('S'), REJECTED('R');

        private final char code;

        Kind(char code) {
            this.code = code;
        }

        private static Kind decode(char code) {
            return switch (code) {
                case 'I' -> INSTRUCTION;
                case 'A' -> ACCEPTED;
                case 'S' -> SETTLED;
                case 'R' -> REJECTED;
                default -> throw new IllegalArgumentException("Unknown clearing record type");
            };
        }
    }

    public ClearingRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(paymentId, "paymentId");
        if (amountMinor < 1 || amountMinor > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Clearing amount is out of range");
        }
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid clearing identifier");
        }
    }

    public byte[] encode() {
        return String.format(Locale.ROOT, "01%c%sAUD%012d%s", kind.code, paymentId, amountMinor, identifier)
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static ClearingRecord decode(byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH) {
            throw new IllegalArgumentException("Incorrect clearing record length");
        }
        String record = new String(bytes, StandardCharsets.US_ASCII);
        if (!record.startsWith("01") || !record.startsWith("AUD", 39)) {
            throw new IllegalArgumentException("Unsupported clearing version or currency");
        }
        String id = record.substring(3, 39);
        String amount = record.substring(42, 54);
        if (!PAYMENT_ID.matcher(id).matches() || !AMOUNT.matcher(amount).matches()) {
            throw new IllegalArgumentException("Invalid clearing payment ID or amount");
        }
        return new ClearingRecord(Kind.decode(record.charAt(2)), UUID.fromString(id), Long.parseLong(amount),
                record.substring(54));
    }
}
