package kz.kbtu.blog.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import kz.kbtu.blog.domain.exception.InvalidInputException;

/** Position of the last row on a page. Clients treat the encoded form as opaque. */
record Cursor(Instant createdAt, long id) {

    String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        byte[] raw = (micros + ":" + id).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Returns null for a missing cursor, which means "start from the first page". */
    static Cursor decodeOrNull(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 2) {
                throw new InvalidInputException("cursor is malformed");
            }
            long micros = Long.parseLong(parts[0]);
            // Real rows are never before 1970, and a forged date could be out of PostgreSQL's range.
            if (micros < 0) {
                throw new InvalidInputException("cursor is malformed");
            }
            return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), Long.parseLong(parts[1]));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("cursor is malformed");
        }
    }
}
