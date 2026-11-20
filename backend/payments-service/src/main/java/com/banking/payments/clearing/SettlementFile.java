package com.banking.payments.clearing;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Clearing evidence for one business date. Records are US-ASCII with LF delimiters. */
public record SettlementFile(LocalDate businessDate, int sequence, List<ClearingRecord> records) {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern HEADER = Pattern.compile("01H[0-9]{14}AUD");
    private static final Pattern TRAILER = Pattern.compile("01T[0-9]{24}");
    public static final int MAX_RECORDS = 10_000;
    public static final int MAX_BYTES = (ClearingRecord.LENGTH + 1) * MAX_RECORDS + 49;

    public SettlementFile {
        Objects.requireNonNull(businessDate, "businessDate");
        if (businessDate.getYear() < 1 || businessDate.getYear() > 9999 || sequence < 1 || sequence > 999_999) {
            throw new IllegalArgumentException("Invalid business date or file sequence");
        }
        records = List.copyOf(records);
        if (records.size() > MAX_RECORDS || records.stream().anyMatch(record -> record.kind() != ClearingRecord.Kind.SETTLED)) {
            throw new IllegalArgumentException("Invalid settlement records");
        }
    }

    public byte[] encode() {
        StringBuilder output = new StringBuilder();
        output.append(String.format(Locale.ROOT, "01H%s%06dAUD\n", DATE.format(businessDate), sequence));
        for (ClearingRecord record : records) {
            output.append(new String(record.encode(), StandardCharsets.US_ASCII)).append('\n');
        }
        output.append(String.format(Locale.ROOT, "01T%06d%018d\n", records.size(), totalMinor()));
        return output.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public static SettlementFile decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Settlement file is too large or absent");
        }
        String[] lines = new String(bytes, StandardCharsets.ISO_8859_1).split("\n", -1);
        if (lines.length < 3 || !lines[lines.length - 1].isEmpty() || !HEADER.matcher(lines[0]).matches()) {
            throw new IllegalArgumentException("Invalid settlement file header or framing");
        }
        String trailer = lines[lines.length - 2];
        int count = lines.length - 3;
        if (!TRAILER.matcher(trailer).matches() || count > MAX_RECORDS
                || Integer.parseInt(trailer.substring(3, 9)) != count) {
            throw new IllegalArgumentException("Invalid settlement record count");
        }

        List<ClearingRecord> records = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            records.add(ClearingRecord.decode(lines[index].getBytes(StandardCharsets.ISO_8859_1)));
        }
        LocalDate date;
        try {
            date = LocalDate.parse(lines[0].substring(3, 11), DATE);
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("Invalid settlement business date", error);
        }
        SettlementFile file = new SettlementFile(date, Integer.parseInt(lines[0].substring(11, 17)), records);
        if (file.totalMinor() != Long.parseLong(trailer.substring(9))) {
            throw new IllegalArgumentException("Invalid settlement control total");
        }
        return file;
    }

    public long totalMinor() {
        return records.stream().mapToLong(ClearingRecord::amountMinor).sum();
    }
}
