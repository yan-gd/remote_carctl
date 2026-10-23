package org.example.patrol.app;

import java.time.*;

/** A clock the test moves by hand. */
final class TestClock extends Clock {
    private Instant now = Instant.parse("2026-10-23T00:00:00Z");

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    void advance(long millis) {
        now = now.plusMillis(millis);
    }
}
