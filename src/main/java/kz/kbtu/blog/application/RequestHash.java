package kz.kbtu.blog.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the business fields, used to detect a key reused with different data.
 * It hashes fields rather than raw request bytes, so REST and gRPC produce the same hash.
 */
final class RequestHash {

    private RequestHash() {
    }

    static String of(String... fields) {
        MessageDigest digest = sha256();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            // The length prefix keeps ("ab", "c") and ("a", "bc") from hashing the same.
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
