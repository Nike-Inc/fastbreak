package com.nike.fastbreak;

import org.junit.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.nike.fastbreak.CircuitBreakerImplTest.assertDefaultGeneratedCircuitBreakerId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link CircuitBreakerForHttpStatusCode}
 *
 * @author Nic Munroe
 */
public class CircuitBreakerForHttpStatusCodeTest {

    @Test
    public void kitchen_sink_constructor_uses_non_empty_arguments_passed_in() {
        // given
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ExecutorService scne = mock(ExecutorService.class);
        Integer maxFailures = 42;
        Duration resetTimeout = Duration.ofSeconds(42);
        Optional<Duration> callTimeout = Optional.of(Duration.ofSeconds(4242));
        String id = UUID.randomUUID().toString();

        // when
        CircuitBreakerForHttpStatusCode cb =
            new CircuitBreakerForHttpStatusCode(Optional.of(scheduler), Optional.of(scne), Optional.of(maxFailures),
                                                Optional.of(resetTimeout), callTimeout, Optional.of(id));

        // then
        assertThat(cb.scheduler).isSameAs(scheduler);
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(scne);
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(maxFailures);
        assertThat(cb.resetTimeoutNanos).isEqualTo(resetTimeout.toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(callTimeout.map(Duration::toNanos));
        assertThat(cb.id).isSameAs(id);
        assertThat(cb.breakingEventStrategy).isSameAs(cb.DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY);
        assertThat(cb.breakingExceptionStrategy).isSameAs(cb.DEFAULT_BREAKING_EXCEPTION_STRATEGY);
    }

    @Test
    public void kitchen_sink_constructor_uses_defaults_for_empty_args() {
        // when
        CircuitBreakerForHttpStatusCode cb =
            new CircuitBreakerForHttpStatusCode(Optional.empty(), Optional.empty(), Optional.empty(),
                                                Optional.empty(), Optional.empty(), Optional.empty());

        // then
        assertThat(cb.scheduler).isSameAs(cb.getDefaultScheduledExecutorService());
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(cb.getDefaultStateChangeNotificationExecutorService());
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(cb.FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED);
        assertThat(cb.resetTimeoutNanos).isEqualTo(Duration.ofSeconds(cb.DEFAULT_RESET_TIMEOUT_SECONDS).toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(Optional.empty());
        assertThat(cb.breakingEventStrategy).isSameAs(cb.DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY);
        assertThat(cb.breakingExceptionStrategy).isSameAs(cb.DEFAULT_BREAKING_EXCEPTION_STRATEGY);
        assertDefaultGeneratedCircuitBreakerId(cb);
    }

    @Test
    public void default_constructor_uses_defaults_for_everything() {
        // when
        CircuitBreakerForHttpStatusCode cb = new CircuitBreakerForHttpStatusCode();

        // then
        assertThat(cb.scheduler).isSameAs(cb.getDefaultScheduledExecutorService());
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(cb.getDefaultStateChangeNotificationExecutorService());
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(cb.FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED);
        assertThat(cb.resetTimeoutNanos).isEqualTo(Duration.ofSeconds(cb.DEFAULT_RESET_TIMEOUT_SECONDS).toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(Optional.empty());
        assertThat(cb.breakingEventStrategy).isSameAs(cb.DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY);
        assertThat(cb.breakingExceptionStrategy).isSameAs(cb.DEFAULT_BREAKING_EXCEPTION_STRATEGY);
        assertDefaultGeneratedCircuitBreakerId(cb);
    }

    @Test
    public void getDefaultHttpStatusCodeCircuitBreakerForKey_creates_and_stores_default_instances() {
        // given
        String key = UUID.randomUUID().toString();

        // when
        CircuitBreakerForHttpStatusCode cb =
            (CircuitBreakerForHttpStatusCode) CircuitBreakerForHttpStatusCode
                .getDefaultHttpStatusCodeCircuitBreakerForKey(key);

        // then
        assertThat(cb.scheduler).isSameAs(cb.getDefaultScheduledExecutorService());
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(cb.getDefaultStateChangeNotificationExecutorService());
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(cb.FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED);
        assertThat(cb.resetTimeoutNanos).isEqualTo(Duration.ofSeconds(cb.DEFAULT_RESET_TIMEOUT_SECONDS).toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(Optional.empty());
        assertThat(cb.id).isEqualTo("default-http-status-cb-for-" + key);
        assertThat(cb.breakingEventStrategy).isSameAs(cb.DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY);
        assertThat(cb.breakingExceptionStrategy).isSameAs(cb.DEFAULT_BREAKING_EXCEPTION_STRATEGY);

        // and when
        CircuitBreaker<Integer>
            subsequentCallCb = CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey(key);

        // then
        assertThat(subsequentCallCb).isSameAs(cb);

        // and when
        CircuitBreakerForHttpStatusCode cbFromOtherMethod =
            (CircuitBreakerForHttpStatusCode) CircuitBreakerForHttpStatusCode
                .getDefaultHttpStatusCodeCircuitBreakerForKey(
                    key,
                    Optional.of(mock(ScheduledExecutorService.class)),
                    Optional.of(mock(ExecutorService.class))
                );

        // then
        assertThat(cbFromOtherMethod).isSameAs(cb);
        // Since it was first created without custom scheduler and executor service it continues to stay that way
        assertThat(cbFromOtherMethod.scheduler).isSameAs(cb.getDefaultScheduledExecutorService());
        assertThat(cbFromOtherMethod.stateChangeNotificationExecutor)
            .isSameAs(cb.getDefaultStateChangeNotificationExecutorService());
    }

    @Test
    public void getDefaultHttpStatusCodeCircuitBreakerForKey_with_custom_scheduler_and_executor_service_creates_and_stores_instances() {
        // given
        String key = UUID.randomUUID().toString();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ExecutorService scne = mock(ExecutorService.class);

        // when
        CircuitBreakerForHttpStatusCode cb =
            (CircuitBreakerForHttpStatusCode) CircuitBreakerForHttpStatusCode
                .getDefaultHttpStatusCodeCircuitBreakerForKey(key,
                                                              Optional.of(scheduler),
                                                              Optional.of(scne));

        // then
        assertThat(cb.scheduler).isSameAs(scheduler);
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(scne);
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(cb.FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED);
        assertThat(cb.resetTimeoutNanos).isEqualTo(Duration.ofSeconds(cb.DEFAULT_RESET_TIMEOUT_SECONDS).toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(Optional.empty());
        assertThat(cb.id).isEqualTo("default-http-status-cb-for-" + key);
        assertThat(cb.breakingEventStrategy).isSameAs(cb.DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY);
        assertThat(cb.breakingExceptionStrategy).isSameAs(cb.DEFAULT_BREAKING_EXCEPTION_STRATEGY);

        // and when
        CircuitBreaker<Integer> subsequentCallCb =
            CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey(key,
                                                                                         Optional.of(scheduler),
                                                                                         Optional.of(scne));

        // then
        assertThat(subsequentCallCb).isSameAs(cb);

        // and when
        CircuitBreakerForHttpStatusCode cbFromOtherMethod =
            (CircuitBreakerForHttpStatusCode) CircuitBreakerForHttpStatusCode
                .getDefaultHttpStatusCodeCircuitBreakerForKey(key);

        // then
        assertThat(cbFromOtherMethod).isSameAs(cb);
        // Since it was first created with custom scheduler and executor service it continues to stay that way
        assertThat(cbFromOtherMethod.scheduler).isSameAs(scheduler);
        assertThat(cbFromOtherMethod.stateChangeNotificationExecutor).isSameAs(scne);
    }

    @Test
    public void getDefaultHttpStatusCodeCircuitBreakerForKey_methods_convert_null_key_to_blank_string() {
        // given
        Optional<ScheduledExecutorService> scheduler = Optional.of(mock(ScheduledExecutorService.class));
        Optional<ExecutorService> scne = Optional.of(mock(ExecutorService.class));

        // when
        CircuitBreaker<Integer> nullKeyDefaultMethodResult =
            CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey(null);
        CircuitBreaker<Integer> emptyKeyDefaultMethodResult =
            CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey("");
        CircuitBreaker<Integer> nullKeyCustomMethodResult =
            CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey(null, scheduler, scne);
        CircuitBreaker<Integer> emptyKeyCustomMethodResult =
            CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey("", scheduler, scne);

        // then
        assertThat(emptyKeyDefaultMethodResult).isSameAs(nullKeyDefaultMethodResult);
        assertThat(nullKeyCustomMethodResult).isSameAs(nullKeyDefaultMethodResult);
        assertThat(emptyKeyCustomMethodResult).isSameAs(nullKeyDefaultMethodResult);
    }

    @Test
    public void event_strategy_works_as_expected() {
        // given
        CircuitBreaker.BreakingEventStrategy<Integer> dm =
            CircuitBreakerForHttpStatusCode.DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY;

        // expect
        assertThat(dm.isEventACircuitBreakerFailure(0)).isFalse();
        assertThat(dm.isEventACircuitBreakerFailure(200)).isFalse();
        assertThat(dm.isEventACircuitBreakerFailure(499)).isFalse();
        assertThat(dm.isEventACircuitBreakerFailure(500)).isTrue();
        assertThat(dm.isEventACircuitBreakerFailure(501)).isTrue();
        assertThat(dm.isEventACircuitBreakerFailure(9999999)).isTrue();
        assertThat(dm.isEventACircuitBreakerFailure(null)).isTrue();
    }
}