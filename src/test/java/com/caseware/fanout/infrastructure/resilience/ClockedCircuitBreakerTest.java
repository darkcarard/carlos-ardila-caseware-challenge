package com.caseware.fanout.infrastructure.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.caseware.fanout.doubles.MutableClock;
import com.caseware.fanout.domain.port.CircuitBreaker.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ClockedCircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final ClockedCircuitBreaker breaker = new ClockedCircuitBreaker(clock, 3, Duration.ofSeconds(30));

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        fail(2);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        fail(1);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void successResetsTheConsecutiveFailureCount() {
        fail(2);
        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.recordSuccess();
        fail(2);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void admitsExactlyOneProbeAfterTheCoolDown() {
        fail(3);
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquirePermission()).isFalse();
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ofSeconds(1));

        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse(); // probe already in flight
        assertThat(breaker.retryAfter()).isZero();
    }

    @Test
    void successfulProbeCloses() {
        fail(3);
        clock.advance(Duration.ofSeconds(30));
        breaker.tryAcquirePermission();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquirePermission()).isTrue();
    }

    @Test
    void failedProbeReopensWithAFreshWindow() {
        fail(3);
        clock.advance(Duration.ofSeconds(30));
        breaker.tryAcquirePermission();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void lateOutcomesFromCallsAdmittedBeforeOpeningDoNotChangeAnOpenBreaker() {
        fail(3);
        clock.advance(Duration.ofSeconds(10));
        breaker.recordSuccess(); // a slow call that started while CLOSED
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void releasingACancelledProbeFreesTheSlotWithoutClosing() {
        fail(3);
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.releasePermission();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquirePermission()).isTrue(); // a new probe may go
    }

    @Test
    void releasingWhileClosedOrOpenIsANoOp() {
        breaker.releasePermission();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        fail(3);
        breaker.releasePermission();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void retryAfterCountsDownAndNeverGoesNegative() {
        fail(3);
        clock.advance(Duration.ofSeconds(20));
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(25)); // cool-down over, but nobody has asked for the probe yet
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ZERO);
    }

    @Test
    void thresholdOfOneOpensOnTheFirstFailure() {
        ClockedCircuitBreaker hair = new ClockedCircuitBreaker(clock, 1, Duration.ofSeconds(1));
        hair.recordFailure();
        assertThat(hair.state()).isEqualTo(State.OPEN);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClockedCircuitBreaker(clock, 0, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClockedCircuitBreaker(clock, 1, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClockedCircuitBreaker(clock, 1, Duration.ofSeconds(-1)));
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            assertThat(breaker.tryAcquirePermission()).isTrue();
            breaker.recordFailure();
        }
    }
}
