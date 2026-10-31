package kz.kbtu.blog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestHashTest {

    @Test
    void sameFieldsGiveTheSameHash() {
        assertThat(RequestHash.of("Title", "Content")).isEqualTo(RequestHash.of("Title", "Content"));
    }

    @Test
    void differentFieldsGiveDifferentHashes() {
        assertThat(RequestHash.of("Title", "Content")).isNotEqualTo(RequestHash.of("Title", "Other"));
    }

    @Test
    void movingTextBetweenFieldsChangesTheHash() {
        assertThat(RequestHash.of("ab", "c")).isNotEqualTo(RequestHash.of("a", "bc"));
    }

    @Test
    void hashIsLowercaseHexOfSha256() {
        assertThat(RequestHash.of("Сәлем 🌍")).hasSize(64).matches("[0-9a-f]+");
    }
}
