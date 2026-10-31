package kz.kbtu.blog.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class Timestamps {

    private Timestamps() {
    }

    /**
     * Java clocks can be finer than PostgreSQL's microseconds. Truncating first means the
     * value we return is exactly the value stored, which cursors and replays depend on.
     */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
