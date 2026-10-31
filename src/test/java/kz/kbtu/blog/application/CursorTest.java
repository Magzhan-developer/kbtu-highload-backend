package kz.kbtu.blog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import kz.kbtu.blog.domain.exception.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void encodingRoundTripsWithMicrosecondPrecision() {
        Cursor cursor = new Cursor(Instant.parse("2026-10-31T10:15:30.123456Z"), 42L);

        assertThat(Cursor.decodeOrNull(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void missingCursorMeansFirstPage() {
        assertThat(Cursor.decodeOrNull(null)).isNull();
        assertThat(Cursor.decodeOrNull("")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "bm90LWEtY3Vyc29y"})
    void malformedCursorIsInvalidInput(String encoded) {
        assertThatThrownBy(() -> Cursor.decodeOrNull(encoded))
                .isInstanceOf(InvalidInputException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1:2:3", "abc:1", "1:abc", "99999999999999999999:1", "-9223372036854775808:1"})
    void wellEncodedGarbageIsInvalidInput(String raw) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Cursor.decodeOrNull(encoded))
                .isInstanceOf(InvalidInputException.class);
    }
}
