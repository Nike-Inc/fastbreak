package com.nike.fastbreak;

import com.nike.fastbreak.CircuitBreaker.BreakingEventStrategy;
import com.nike.fastbreak.CircuitBreaker.BreakingExceptionStrategy;
import com.nike.fastbreak.CircuitBreakerImpl.State;
import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.fastbreak.exception.CircuitBreakerTimeoutException;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.nike.fastbreak.CircuitBreaker.DEFAULT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link CircuitBreakerImpl}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class CircuitBreakerImplTest {

    private CircuitBreakerImpl<String> cbSpy;

    @Before
    public void beforeMethod() {
        cbSpy = spy(new CircuitBreakerImpl<>());
    }

    @Test
    public void code_coverage_hoops() {
        // jump!
        for (CircuitBreakerImpl.State state : CircuitBreakerImpl.State.values()) {
            assertThat(CircuitBreakerImpl.State.valueOf(state.name())).isEqualTo(state);
        }
    }

    public void verifyDefaultsForEverything(CircuitBreakerImpl cb) {
        assertThat(cb.scheduler).isSameAs(cb.getDefaultScheduledExecutorService());
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(cb.getDefaultStateChangeNotificationExecutorService());
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(cb.FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED);
        assertThat(cb.resetTimeoutNanos).isEqualTo(Duration.ofSeconds(cb.DEFAULT_RESET_TIMEOUT_SECONDS).toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(Optional.empty());
        assertThat(cb.breakingEventStrategy).isSameAs(cb.DEFAULT_BREAKING_EVENT_STRATEGY);
        assertThat(cb.breakingExceptionStrategy).isSameAs(cb.DEFAULT_BREAKING_EXCEPTION_STRATEGY);
        assertDefaultGeneratedCircuitBreakerId(cb);
    }

    @Test
    public void kitchen_sink_constructor_uses_non_empty_arguments_passed_in() {
        // given
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ExecutorService scne = mock(ExecutorService.class);
        Integer maxFailures = 42;
        Duration resetTimeout = Duration.ofSeconds(42);
        Optional<Duration> callTimeout = Optional.of(Duration.ofSeconds(4242));
        String id = UUID.randomUUID().toString();
        BreakingEventStrategy<String> eventStrategy = mock(BreakingEventStrategy.class);
        BreakingExceptionStrategy errorStrategy = mock(BreakingExceptionStrategy.class);

        // when
        CircuitBreakerImpl<String> cb =
            new CircuitBreakerImpl<>(
                Optional.of(scheduler), Optional.of(scne), Optional.of(maxFailures), Optional.of(resetTimeout),
                callTimeout, Optional.of(id), Optional.of(eventStrategy), Optional.of(errorStrategy)
            );

        // then
        assertThat(cb.scheduler).isSameAs(scheduler);
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(scne);
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(maxFailures);
        assertThat(cb.resetTimeoutNanos).isEqualTo(resetTimeout.toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(callTimeout.map(Duration::toNanos));
        assertThat(cb.id).isSameAs(id);
        assertThat(cb.breakingEventStrategy).isSameAs(eventStrategy);
        assertThat(cb.breakingExceptionStrategy).isSameAs(errorStrategy);
    }

    @Test
    public void kitchen_sink_constructor_uses_defaults_for_empty_args() {
        // when
        CircuitBreakerImpl<String> cb =
            new CircuitBreakerImpl<>(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()
            );

        // then
        verifyDefaultsForEverything(cb);
    }

    @Test
    public void newManualModeTask_and_originatingCircuitBreaker_returns_original_circuit_breaker_instance() {
        // given
        CircuitBreakerImpl<String> cb = new CircuitBreakerImpl<>();

        // when
        CircuitBreaker.ManualModeTask<String> mmt = cb.newManualModeTask();

        // then
        assertThat(mmt).isSameAs(cb);
        assertThat(mmt.originatingCircuitBreaker()).isSameAs(cb);
    }

    @Test
    public void default_constructor_uses_defaults_for_everything() {
        // when
        CircuitBreakerImpl<String> cb = new CircuitBreakerImpl<>();

        // then
        verifyDefaultsForEverything(cb);
    }

    @Test
    public void default_event_strategy_works_as_expected() {
        // given
        BreakingEventStrategy<Object> dm =
            (BreakingEventStrategy<Object>) CircuitBreakerImpl.DEFAULT_BREAKING_EVENT_STRATEGY;

        // expect
        assertThat(dm.isEventACircuitBreakerFailure(new Object())).isFalse();
        assertThat(dm.isEventACircuitBreakerFailure("foo")).isFalse();
        assertThat(dm.isEventACircuitBreakerFailure(null)).isFalse();
    }

    @Test
    public void default_exception_strategy_works_as_expected() {
        // given
        BreakingExceptionStrategy dm = CircuitBreakerImpl.DEFAULT_BREAKING_EXCEPTION_STRATEGY;

        // expect
        assertThat(dm.isExceptionACircuitBreakerFailure(new Throwable())).isTrue();
        assertThat(dm.isExceptionACircuitBreakerFailure(new Exception())).isTrue();
        assertThat(dm.isExceptionACircuitBreakerFailure(new RuntimeException())).isTrue();
        assertThat(dm.isExceptionACircuitBreakerFailure(null)).isTrue();
    }

    @Test
    public void default_scheduler_has_setRemoveOnCancelPolicy_set_to_true() {
        // given
        ScheduledExecutorService defaultScheduler = new CircuitBreakerImpl<>().getDefaultScheduledExecutorService();

        // expect
        assertThat(defaultScheduler).isInstanceOf(ScheduledThreadPoolExecutor.class);
        assertThat(((ScheduledThreadPoolExecutor) defaultScheduler).getRemoveOnCancelPolicy()).isTrue();
    }

    @Test
    public void throwExceptionIfCircuitBreakerIsOpen_throws_CircuitBreakerOpenException_if_circuit_is_OPEN() {
        // given
        Whitebox.setInternalState(cbSpy, "currentState", State.OPEN);

        // when
        Throwable cbExplosion = catchThrowable(cbSpy::throwExceptionIfCircuitBreakerIsOpen);

        // then
        assertThat(cbExplosion).isNotNull();
        assertThat(cbExplosion).isInstanceOf(CircuitBreakerOpenException.class);
        verify(cbSpy, times(0)).scheduleHalfOpenStateTimeout();
    }

    @Test
    public void throwExceptionIfCircuitBreakerIsOpen_does_nothing_if_circuit_is_CLOSED() {
        // given
        Whitebox.setInternalState(cbSpy, "currentState", State.CLOSED);

        // when
        Throwable cbExplosion = catchThrowable(cbSpy::throwExceptionIfCircuitBreakerIsOpen);

        // then
        assertThat(cbExplosion).isNull();
        verify(cbSpy, times(0)).scheduleHalfOpenStateTimeout();
    }

    @Test
    public void throwExceptionIfCircuitBreakerIsOpen_schedules_half_open_check_and_does_not_explode_if_circuit_is_half_open() {
        // given
        Whitebox.setInternalState(cbSpy, "currentState", State.OPEN);
        ((AtomicBoolean) Whitebox.getInternalState(cbSpy, "halfOpenAllowSingleCall")).set(true);

        // when
        Throwable cbExplosion = catchThrowable(cbSpy::throwExceptionIfCircuitBreakerIsOpen);

        // then
        assertThat(cbExplosion).isNull();
        verify(cbSpy).scheduleHalfOpenStateTimeout();
    }

    @Test
    public void isEventACircuitBreakerFailure_returns_whatever_breakingEventStrategy_returns() {
        // given
        BreakingEventStrategy<String> falseDecider = theString -> false;
        Whitebox.setInternalState(cbSpy, "breakingEventStrategy", falseDecider);

        // expect
        assertThat(cbSpy.isEventACircuitBreakerFailure("foo")).isFalse();

        // and given
        BreakingEventStrategy<String> trueDecider = theString -> true;
        Whitebox.setInternalState(cbSpy, "breakingEventStrategy", trueDecider);

        // expect
        assertThat(cbSpy.isEventACircuitBreakerFailure("foo")).isTrue();
    }

    @Test
    public void isExceptionACircuitBreakerFailure_returns_whatever_breakingEventStrategy_returns() {
        // given
        Throwable someThrowable = new Throwable();
        BreakingExceptionStrategy falseDecider = ex -> false;
        Whitebox.setInternalState(cbSpy, "breakingExceptionStrategy", falseDecider);

        // expect
        assertThat(cbSpy.isExceptionACircuitBreakerFailure(someThrowable)).isFalse();

        // and given
        BreakingExceptionStrategy trueDecider = ex -> true;
        Whitebox.setInternalState(cbSpy, "breakingExceptionStrategy", trueDecider);

        // expect
        assertThat(cbSpy.isExceptionACircuitBreakerFailure(someThrowable)).isTrue();
    }

    @Test
    public void handleEvent_calls_processFailureCall_if_isEventACircuitBreakerFailure_returns_true() {
        // given
        doReturn(true).when(cbSpy).isEventACircuitBreakerFailure(anyString());

        // when
        cbSpy.handleEvent("foo");

        // then
        verify(cbSpy).processFailureCall();
    }

    @Test
    public void handleEvent_calls_processSuccessfulCall_if_isEventACircuitBreakerFailure_returns_false() {
        // given
        doReturn(false).when(cbSpy).isEventACircuitBreakerFailure(anyString());

        // when
        cbSpy.handleEvent("foo");

        // then
        verify(cbSpy).processSuccessfulCall();
    }

    @Test
    public void handleEvent_logs_error_but_does_not_explode_if_unexpected_error_occurs() {
        // given
        Logger loggerMock = mock(Logger.class);
        Whitebox.setInternalState(cbSpy, "logger", loggerMock);
        RuntimeException unexpectedEx = new RuntimeException("kablammo");
        doThrow(unexpectedEx).when(cbSpy).isEventACircuitBreakerFailure(anyString());

        // when
        Throwable cbExplosion = catchThrowable(() -> cbSpy.handleEvent("foo"));

        // then
        assertThat(cbExplosion).isNull();
        verify(loggerMock).error(anyString(), eq(unexpectedEx));
    }

    @Test
    public void handleException_calls_processFailureCall_if_isExceptionACircuitBreakerFailure_returns_true() {
        // given
        doReturn(true).when(cbSpy).isExceptionACircuitBreakerFailure(any());

        // when
        cbSpy.handleException(new Throwable());

        // then
        verify(cbSpy).processFailureCall();
    }

    @Test
    public void handleException_calls_processSuccessfulCall_if_isExceptionACircuitBreakerFailure_returns_false() {
        // given
        doReturn(false).when(cbSpy).isExceptionACircuitBreakerFailure(any());

        // when
        cbSpy.handleException(new Throwable());

        // then
        verify(cbSpy).processSuccessfulCall();
    }

    @Test
    public void handleException_logs_error_but_does_not_explode_if_unexpected_error_occurs() {
        // given
        Logger loggerMock = mock(Logger.class);
        Whitebox.setInternalState(cbSpy, "logger", loggerMock);
        RuntimeException unexpectedEx = new RuntimeException("kablammo");
        doThrow(unexpectedEx).when(cbSpy).isExceptionACircuitBreakerFailure(any());

        // when
        Throwable cbExplosion = catchThrowable(() -> cbSpy.handleException(new Throwable()));

        // then
        assertThat(cbExplosion).isNull();
        verify(loggerMock).error(anyString(), eq(unexpectedEx));
    }

    @Test
    public void handleException_does_nothing_if_arg_is_CircuitBreakerOpenException() {
        // given
        CircuitBreakerOpenException ex = mock(CircuitBreakerOpenException.class);

        // when
        cbSpy.handleException(ex);

        // then
        verify(cbSpy).handleException(ex);
        verify(cbSpy).unwrapAsyncExceptions(ex);
        verifyNoMoreInteractions(cbSpy);
    }

    @Test
    public void handleException_unwraps_exception_before_doing_anything_else() {
        // given
        Throwable underlyingError = new Throwable("clunk");
        CompletionException wrapperEx = new CompletionException(underlyingError);

        // when
        cbSpy.handleException(wrapperEx);

        // then
        verify(cbSpy).unwrapAsyncExceptions(wrapperEx);
        verify(cbSpy).isExceptionACircuitBreakerFailure(underlyingError);
    }

    @Test
    public void handleException_unwraps_exception_before_doing_CircuitBreakerOpenException_check() {
        // given
        CircuitBreakerOpenException underlyingError = mock(CircuitBreakerOpenException.class);
        CompletionException wrapperEx = new CompletionException(underlyingError);

        // when
        cbSpy.handleException(wrapperEx);

        // then
        verify(cbSpy).handleException(wrapperEx);
        verify(cbSpy).unwrapAsyncExceptions(wrapperEx);
        verify(cbSpy).unwrapAsyncExceptions(underlyingError);
        verifyNoMoreInteractions(cbSpy);
    }

    @Test
    public void unwrapAsyncExceptions_returns_null_if_passed_null() {
        // expect
        assertThat(cbSpy.unwrapAsyncExceptions(null)).isNull();
    }

    @Test
    public void unwrapAsyncExceptions_returns_passed_in_arg_if_exception_has_no_cause() {
        // given
        Exception noCause = new Exception("boom");

        // expect
        assertThat(cbSpy.unwrapAsyncExceptions(noCause)).isSameAs(noCause);
    }

    @Test
    public void unwrapAsyncExceptions_unwraps_CompletionException() {
        // given
        Exception underlyingError = new Exception("bang");
        CompletionException wrapperEx = new CompletionException(underlyingError);

        // expect
        assertThat(cbSpy.unwrapAsyncExceptions(wrapperEx)).isSameAs(underlyingError);
    }

    @Test
    public void unwrapAsyncExceptions_unwraps_ExecutionException() {
        // given
        Exception underlyingError = new Exception("bang");
        ExecutionException wrapperEx = new ExecutionException(underlyingError);

        // expect
        assertThat(cbSpy.unwrapAsyncExceptions(wrapperEx)).isSameAs(underlyingError);
    }

    @Test
    public void unwrapAsyncExceptions_unwraps_recursively() {
        // given
        Exception underlyingError = new Exception("bang");
        ExecutionException wrapperEx = new ExecutionException(underlyingError);
        CompletionException doubleWrapperEx = new CompletionException(wrapperEx);

        // expect
        assertThat(cbSpy.unwrapAsyncExceptions(doubleWrapperEx)).isSameAs(underlyingError);
    }

    @Test
    public void unwrapAsyncExceptions_returns_passed_in_arg_if_exception_has_cause_but_is_not_unwrappable() {
        // given
        Exception wrapperEx = new Exception(new Exception("boom"));

        // expect
        assertThat(cbSpy.unwrapAsyncExceptions(wrapperEx)).isSameAs(wrapperEx);
    }

    @Test
    public void executeAsyncCall_explodes_immediately_if_circuit_is_OPEN() {
        // given
        Supplier<CompletableFuture<String>> supplierMock = mock(Supplier.class);
        Whitebox.setInternalState(cbSpy, "currentState", State.OPEN);

        // when
        Throwable cbExplosion = catchThrowable(() -> cbSpy.executeAsyncCall(supplierMock));

        // then
        assertThat(cbExplosion)
            .isNotNull()
            .isInstanceOf(CircuitBreakerOpenException.class);
        verifyZeroInteractions(supplierMock);
    }

    @Test
    public void executeAsyncCall_returns_future_from_supplier() {
        // given
        String uuid = UUID.randomUUID().toString();
        CompletableFuture<String> future = CompletableFuture.completedFuture(uuid);
        Supplier<CompletableFuture<String>> supplier = () -> future;

        // when
        CompletableFuture<String> result = cbSpy.executeAsyncCall(supplier);

        // then
        assertThat(result).isSameAs(future);
        assertThat(result.join()).isEqualTo(uuid);
    }

    @Test
    public void executeAsyncCall_calls_handleEvent_when_future_completes_if_future_returns_event() {
        // given
        String uuid = UUID.randomUUID().toString();
        CompletableFuture<String> future = CompletableFuture.completedFuture(uuid);
        Supplier<CompletableFuture<String>> supplier = () -> future;

        // when
        CompletableFuture<String> result = cbSpy.executeAsyncCall(supplier);
        result.join();

        // then
        verify(cbSpy).handleEvent(uuid);
    }

    @Test
    public void executeAsyncCall_returns_future_and_calls_handleException_when_future_completes_with_exception()
        throws InterruptedException {
        // given
        RuntimeException exToThrow = new RuntimeException("splat");
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            throw exToThrow;
        });
        Supplier<CompletableFuture<String>> supplier = () -> future;

        // when
        CompletableFuture<String> result = cbSpy.executeAsyncCall(supplier);
        Exception exThrownByFuture = null;
        try {
            result.join();
        }
        catch (Exception ex) {
            exThrownByFuture = ex;
        }

        // then
        Thread.sleep(50);
        assertThat(result.isCompletedExceptionally()).isTrue();
        assertThat(exThrownByFuture)
            .isNotNull()
            .isInstanceOf(CompletionException.class)
            .hasCause(exToThrow);
        ArgumentCaptor<Throwable> handleExceptionArgumentCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(cbSpy).handleException(handleExceptionArgumentCaptor.capture());
        Throwable argPassedToHandleException = handleExceptionArgumentCaptor.getValue();
        assertThat(argPassedToHandleException)
            .isNotNull()
            .isInstanceOf(CompletionException.class);
        assertThat(cbSpy.unwrapAsyncExceptions(argPassedToHandleException)).isSameAs(exToThrow);
        verify(cbSpy).isExceptionACircuitBreakerFailure(exToThrow);
    }

    @Test
    public void executeAsyncCall_short_circuit_times_out_the_call_if_it_takes_longer_than_callTimeout() {
        // given
        long callTimeoutMillis = 50;
        long callExecutionTimeMillis = 150;
        Whitebox
            .setInternalState(cbSpy, "callTimeoutNanos", Optional.of(Duration.ofMillis(callTimeoutMillis).toNanos()));
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(callExecutionTimeMillis);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "foo";
        });

        // when
        long startTimeMillis = System.currentTimeMillis();
        CompletableFuture<String> result = cbSpy.executeAsyncCall(() -> future);
        Exception exThrownByFuture = null;
        try {
            result.join();
        }
        catch (Exception ex) {
            exThrownByFuture = ex;
        }
        long endTimeMillis = System.currentTimeMillis();

        // then
        assertThat(result.isCompletedExceptionally()).isTrue();
        assertThat(exThrownByFuture)
            .isNotNull()
            .isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(CircuitBreakerTimeoutException.class);
        assertThat((endTimeMillis - startTimeMillis)).isLessThan(callExecutionTimeMillis);
    }

    @Test
    public void executeAsyncCall_does_not_time_out_the_call_if_it_takes_shorter_than_callTimeout()
        throws InterruptedException {
        // given
        long callTimeoutMillis = 100;
        long callExecutionTimeMillis = 50;
        Whitebox
            .setInternalState(cbSpy, "callTimeoutNanos", Optional.of(Duration.ofMillis(callTimeoutMillis).toNanos()));
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(callExecutionTimeMillis);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "foo";
        });

        // when
        long startTimeMillis = System.currentTimeMillis();
        CompletableFuture<String> result = cbSpy.executeAsyncCall(() -> future);
        Exception exThrownByFuture = null;
        try {
            result.join();
        }
        catch (Exception ex) {
            exThrownByFuture = ex;
        }
        long endTimeMillis = System.currentTimeMillis();

        // then
        Thread.sleep(callTimeoutMillis + 1); // Give the call timeout logic time to execute (if it were going to, which it shouldn't)
        assertThat(result.isCompletedExceptionally()).isFalse();
        assertThat(exThrownByFuture).isNull();
        assertThat((endTimeMillis - startTimeMillis)).isGreaterThanOrEqualTo(callExecutionTimeMillis);
    }

    @Test
    public void executeAsyncCall_cancels_timeout_check_if_future_completes_first() throws InterruptedException {
        // given
        long callTimeoutMillis = 100;
        long callExecutionTimeMillis = 50;
        Whitebox
            .setInternalState(cbSpy, "callTimeoutNanos", Optional.of(Duration.ofMillis(callTimeoutMillis).toNanos()));
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(callExecutionTimeMillis);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "foo";
        });
        ScheduledExecutorService schedulerMock = mock(ScheduledExecutorService.class);
        ScheduledFuture scheduledFutureMock = mock(ScheduledFuture.class);
        doReturn(scheduledFutureMock).when(schedulerMock)
                                     .schedule(any(Runnable.class), any(Long.class), any(TimeUnit.class));
        doReturn(false).when(scheduledFutureMock).isDone();
        Whitebox.setInternalState(cbSpy, "scheduler", schedulerMock);

        // when
        CompletableFuture<String> result = cbSpy.executeAsyncCall(() -> future);
        result.join();
        Thread.sleep(50); // have to give the cancellation logic time to run before checking

        // then
        verify(scheduledFutureMock).cancel(false);
    }

    @Test
    public void executeAsyncCall_does_not_cancel_timeout_check_if_future_takes_too_long() throws InterruptedException {
        // given
        long callTimeoutMillis = 100;
        long callExecutionTimeMillis = 150;
        Whitebox
            .setInternalState(cbSpy, "callTimeoutNanos", Optional.of(Duration.ofMillis(callTimeoutMillis).toNanos()));
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(callExecutionTimeMillis);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "foo";
        });
        ScheduledExecutorService schedulerMock = mock(ScheduledExecutorService.class);
        ScheduledFuture scheduledFutureMock = mock(ScheduledFuture.class);
        doReturn(scheduledFutureMock).when(schedulerMock)
                                     .schedule(any(Runnable.class), any(Long.class), any(TimeUnit.class));
        doReturn(true).when(scheduledFutureMock).isDone();
        Whitebox.setInternalState(cbSpy, "scheduler", schedulerMock);

        // when
        CompletableFuture<String> result = cbSpy.executeAsyncCall(() -> future);
        result.join();
        Thread.sleep(50); // have to give the cancellation logic time to run before checking

        // then
        verify(scheduledFutureMock, times(0)).cancel(anyBoolean());
    }

    @Test
    public void executeBlockingCall_explodes_immediately_if_circuit_is_OPEN() {
        // given
        Callable<String> supplierMock = mock(Callable.class);
        Whitebox.setInternalState(cbSpy, "currentState", State.OPEN);

        // when
        Throwable cbExplosion = catchThrowable(() -> cbSpy.executeBlockingCall(supplierMock));

        // then
        assertThat(cbExplosion)
            .isNotNull()
            .isInstanceOf(CircuitBreakerOpenException.class);
        verifyZeroInteractions(supplierMock);
    }

    @Test
    public void executeBlockingCall_calls_handleEvent_and_returns_event_from_supplier() throws Exception {
        // given
        String uuid = UUID.randomUUID().toString();
        Callable<String> supplier = () -> uuid;

        // when
        String result = cbSpy.executeBlockingCall(supplier);

        // then
        verify(cbSpy).handleEvent(uuid);
        assertThat(result).isSameAs(uuid);
    }

    @Test
    public void executeBlockingCall_calls_handleException_on_exception_thrown_by_supplier_and_then_throws_it() {
        // given
        RuntimeException exToThrow = new RuntimeException("splat");
        Callable<String> supplier = () -> {
            throw exToThrow;
        };

        // when
        Throwable cbExplosion = catchThrowable(() -> cbSpy.executeBlockingCall(supplier));

        // then
        assertThat(cbExplosion).isSameAs(exToThrow);
        verify(cbSpy).handleException(exToThrow);
    }

    @Test
    public void executeBlockingCall_throws_CircuitBreakerTimeoutException_after_supplier_completes_if_supplier_takes_longer_than_callTimeout() {
        // given
        long callTimeoutMillis = 50;
        long callExecutionTimeMillis = 100;
        Whitebox
            .setInternalState(cbSpy, "callTimeoutNanos", Optional.of(Duration.ofMillis(callTimeoutMillis).toNanos()));
        Callable<String> supplier = () -> {
            try {
                Thread.sleep(callExecutionTimeMillis);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "foo";
        };

        // when
        long startTimeMillis = System.currentTimeMillis();
        Throwable cbExplosion = catchThrowable(() -> cbSpy.executeBlockingCall(supplier));
        long endTimeMillis = System.currentTimeMillis();

        // then
        assertThat(cbExplosion)
            .isNotNull()
            .isInstanceOf(CircuitBreakerTimeoutException.class);
        assertThat((endTimeMillis - startTimeMillis)).isGreaterThanOrEqualTo(callExecutionTimeMillis);
    }

    @Test
    public void executeBlockingCall_does_not_throw_CircuitBreakerTimeoutException_if_call_takes_shorter_than_callTimeout() {
        // given
        long callTimeoutMillis = 100;
        long callExecutionTimeMillis = 50;
        Whitebox
            .setInternalState(cbSpy, "callTimeoutNanos", Optional.of(Duration.ofMillis(callTimeoutMillis).toNanos()));
        Callable<String> supplier = () -> {
            try {
                Thread.sleep(callExecutionTimeMillis);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "foo";
        };

        // when
        long startTimeMillis = System.currentTimeMillis();
        Throwable cbExplosion = catchThrowable(() -> cbSpy.executeBlockingCall(supplier));
        long endTimeMillis = System.currentTimeMillis();

        // then
        assertThat(cbExplosion).isNull();
        assertThat((endTimeMillis - startTimeMillis)).isGreaterThanOrEqualTo(callExecutionTimeMillis);
    }

    @Test
    public void executeBlockingCall_gives_precedence_to_CircuitBreakerTimeoutException_if_supplier_takes_longer_than_callTimeout_even_if_supplier_throws_exception() {
        // given
        long callTimeoutMillis = 50;
        long callExecutionTimeMillis = 100;
        Whitebox
            .setInternalState(cbSpy, "callTimeoutNanos", Optional.of(Duration.ofMillis(callTimeoutMillis).toNanos()));
        Callable<String> supplier = () -> {
            try {
                Thread.sleep(callExecutionTimeMillis);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            throw new Exception("crunch");
        };

        // when
        long startTimeMillis = System.currentTimeMillis();
        Throwable cbExplosion = catchThrowable(() -> cbSpy.executeBlockingCall(supplier));
        long endTimeMillis = System.currentTimeMillis();

        // then
        assertThat(cbExplosion)
            .isNotNull()
            .isInstanceOf(CircuitBreakerTimeoutException.class);
        assertThat((endTimeMillis - startTimeMillis)).isGreaterThanOrEqualTo(callExecutionTimeMillis);
    }

    @Test
    public void onClose_adds_runnable_to_list_and_returns_self() {
        // given
        Runnable listener = mock(Runnable.class);

        // when
        CircuitBreaker<String> fluentReturn = cbSpy.onClose(listener);

        // then
        assertThat(cbSpy.onCloseListeners).contains(listener);
        assertThat(fluentReturn).isSameAs(cbSpy);
    }

    @Test
    public void onHalfOpen_adds_runnable_to_list_and_returns_self() {
        // given
        Runnable listener = mock(Runnable.class);

        // when
        CircuitBreaker<String> fluentReturn = cbSpy.onHalfOpen(listener);

        // then
        assertThat(cbSpy.onHalfOpenListeners).contains(listener);
        assertThat(fluentReturn).isSameAs(cbSpy);
    }

    @Test
    public void onOpen_adds_runnable_to_list_and_returns_self() {
        // given
        Runnable listener = mock(Runnable.class);

        // when
        CircuitBreaker<String> fluentReturn = cbSpy.onOpen(listener);

        // then
        assertThat(cbSpy.onOpenListeners).contains(listener);
        assertThat(fluentReturn).isSameAs(cbSpy);
    }

    @DataProvider(value = {
        "CLOSED |   0",
        "CLOSED |   1",
        "CLOSED |   9999999",
        "OPEN   |   0",
        "OPEN   |   1",
        "OPEN   |   999999",
    }, splitBy = "\\|")
    @Test
    public void processSuccessfulCall_resets_consecutive_failure_counter_to_0_and_sets_state_to_CLOSED(
        State stateBeforeCall, int failureCountBeforeCall) {
        // given
        cbSpy.currentState = stateBeforeCall;
        cbSpy.consecutiveFailureCount = failureCountBeforeCall;

        // when
        cbSpy.processSuccessfulCall();

        // then
        assertThat(cbSpy.consecutiveFailureCount).isEqualTo(0);
        assertThat(cbSpy.currentState).isEqualTo(State.CLOSED);
    }

    @DataProvider(value = {
        "CLOSED",
        "OPEN"
    }, splitBy = "\\|")
    @Test
    public void processSuccessfulCall_calls_notifyOnCloseListeners_if_state_changed(State stateBeforeCall) {
        // given
        cbSpy.currentState = stateBeforeCall;
        boolean expectStateChange = !stateBeforeCall.equals(State.CLOSED);

        // when
        cbSpy.processSuccessfulCall();

        // then
        if (expectStateChange)
            verify(cbSpy).notifyOnCloseListeners();
        else
            verify(cbSpy, times(0)).notifyOnCloseListeners();
    }

    @DataProvider(value = {
        "OPEN   |   true    |   false",
        "OPEN   |   true    |   true",
        "OPEN   |   false   |   false",
        "CLOSED |   true    |   false",
        "CLOSED |   true    |   true",
        "CLOSED |   false   |   false",
    }, splitBy = "\\|")
    @Test
    public void processSuccessfulCall_cancels_existing_half_open_scheduled_future_if_state_changed_and_future_exists_and_future_is_not_done(
        State stateBeforeCall, boolean futureExists, boolean futureIsDone) {

        // given
        cbSpy.currentState = stateBeforeCall;
        ScheduledFuture futureMock = futureExists ? mock(ScheduledFuture.class) : null;
        cbSpy.halfOpenScheduledFuture.set(futureMock);
        if (futureMock != null)
            doReturn(futureIsDone).when(futureMock).isDone();

        boolean expectStateChange = !stateBeforeCall.equals(State.CLOSED);
        boolean expectFutureToBeCanceled = expectStateChange && futureExists && !futureIsDone;

        // when
        cbSpy.processSuccessfulCall();

        // then
        if (expectStateChange)
            assertThat(cbSpy.halfOpenScheduledFuture.get()).isNull();
        else
            assertThat(cbSpy.halfOpenScheduledFuture.get()).isSameAs(futureMock);

        if (futureExists) {
            if (expectFutureToBeCanceled)
                verify(futureMock).cancel(false);
            else
                verify(futureMock, times(0)).cancel(false);
        }
    }

    @DataProvider(value = {
        "OPEN   |   0   |   10",
        "OPEN   |   9   |   10",
        "OPEN   |   20  |   10",
        "CLOSED |   0   |   10",
        "CLOSED |   9   |   10",
        "CLOSED |   20  |   10",
    }, splitBy = "\\|")
    @Test
    public void processFailureCall_works_as_expected(State stateBeforeCall, int failureCountBeforeCall,
                                                     int maxFailuresAllowed) {
        // given
        cbSpy.currentState = stateBeforeCall;
        cbSpy.consecutiveFailureCount = failureCountBeforeCall;
        Whitebox.setInternalState(cbSpy, "maxConsecutiveFailuresAllowed", maxFailuresAllowed);
        cbSpy.halfOpenAllowSingleCall.set(true);

        boolean expectStateChange =
            !stateBeforeCall.equals(State.OPEN) && (failureCountBeforeCall + 1 >= maxFailuresAllowed);

        // when
        cbSpy.processFailureCall();

        // then
        assertThat(cbSpy.consecutiveFailureCount).isEqualTo(failureCountBeforeCall + 1);
        if (expectStateChange) {
            assertThat(cbSpy.currentState).isEqualTo(State.OPEN);
            verify(cbSpy).scheduleHalfOpenStateTimeout();
            verify(cbSpy).notifyOnOpenListeners();
            assertThat(cbSpy.halfOpenAllowSingleCall.get()).isFalse();
        }
        else {
            assertThat(cbSpy.currentState).isEqualTo(stateBeforeCall);
            verify(cbSpy, times(0)).scheduleHalfOpenStateTimeout();
            verify(cbSpy, times(0)).notifyOnOpenListeners();
            assertThat(cbSpy.halfOpenAllowSingleCall.get()).isTrue();
        }
    }

    @Test
    public void notifyOnCloseListeners_notifies_onClose_listeners() throws InterruptedException {
        // given
        Runnable listener1 = mock(Runnable.class);
        Runnable listener2 = mock(Runnable.class);
        cbSpy.onClose(listener1).onClose(listener2);
        ExecutorService executorServiceMock = mock(ExecutorService.class);
        Whitebox.setInternalState(cbSpy, "stateChangeNotificationExecutor", executorServiceMock);

        // when
        cbSpy.notifyOnCloseListeners();

        // then
        verify(executorServiceMock).execute(listener1);
        verify(executorServiceMock).execute(listener2);
    }

    @Test
    public void notifyOnHalfOpenListeners_notifies_onHalfOpen_listeners() throws InterruptedException {
        // given
        Runnable listener1 = mock(Runnable.class);
        Runnable listener2 = mock(Runnable.class);
        cbSpy.onHalfOpen(listener1).onHalfOpen(listener2);
        ExecutorService executorServiceMock = mock(ExecutorService.class);
        Whitebox.setInternalState(cbSpy, "stateChangeNotificationExecutor", executorServiceMock);

        // when
        cbSpy.notifyOnHalfOpenListeners();

        // then
        verify(executorServiceMock).execute(listener1);
        verify(executorServiceMock).execute(listener2);
    }

    @Test
    public void notifyOnOpenListeners_notifies_onOpen_listeners() throws InterruptedException {
        // given
        Runnable listener1 = mock(Runnable.class);
        Runnable listener2 = mock(Runnable.class);
        cbSpy.onOpen(listener1).onOpen(listener2);
        ExecutorService executorServiceMock = mock(ExecutorService.class);
        Whitebox.setInternalState(cbSpy, "stateChangeNotificationExecutor", executorServiceMock);

        // when
        cbSpy.notifyOnOpenListeners();

        // then
        verify(executorServiceMock).execute(listener1);
        verify(executorServiceMock).execute(listener2);
    }

    @Test
    public void scheduleHalfOpenStateTimeout_schedules_task_to_occur_after_resetTimeoutNanos_nanoseconds() {
        // given
        ScheduledExecutorService schedulerMock = mock(ScheduledExecutorService.class);
        Whitebox.setInternalState(cbSpy, "scheduler", schedulerMock);
        long resetTimeout = 42;
        Whitebox.setInternalState(cbSpy, "resetTimeoutNanos", resetTimeout);

        // when
        cbSpy.scheduleHalfOpenStateTimeout();

        // then
        verify(schedulerMock).schedule(any(Runnable.class), eq(resetTimeout), eq(TimeUnit.NANOSECONDS));
    }

    @DataProvider(value = {
        "OPEN",
        "CLOSED"
    }, splitBy = "\\|")
    @Test
    public void scheduleHalfOpenStateTimeout_schedules_half_open_check_task_that_works_as_expected(
        State stateBeforeCall) {
        // given
        ScheduledExecutorService schedulerMock = mock(ScheduledExecutorService.class);
        Whitebox.setInternalState(cbSpy, "scheduler", schedulerMock);
        cbSpy.scheduleHalfOpenStateTimeout();
        ArgumentCaptor<Runnable> scheduledHalfOpenTaskArgumentCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(schedulerMock)
            .schedule(scheduledHalfOpenTaskArgumentCaptor.capture(), any(Long.class), any(TimeUnit.class));
        cbSpy.halfOpenAllowSingleCall.set(false);

        cbSpy.currentState = stateBeforeCall;
        Runnable scheduledHalfOpenTask = scheduledHalfOpenTaskArgumentCaptor.getValue();

        // when
        scheduledHalfOpenTask.run();

        // then
        if (stateBeforeCall.equals(State.OPEN)) {
            // Half open triggered
            assertThat(cbSpy.halfOpenAllowSingleCall.get()).isTrue();
            verify(cbSpy).notifyOnHalfOpenListeners();
        }
        else {
            // State closed when task ran. Nothing to do.
            assertThat(cbSpy.halfOpenAllowSingleCall.get()).isFalse();
            verify(cbSpy, times(0)).notifyOnHalfOpenListeners();
        }
    }

    // ================= BUILDER RELATED TESTS ====================
    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void empty_builder_results_in_default_circuit_breaker(boolean useFactoryMethod) {
        // given
        CircuitBreakerImpl.Builder<Integer> builder = (useFactoryMethod)
                                                      ? CircuitBreakerImpl.newBuilder()
                                                      : new CircuitBreakerImpl.Builder<>();

        // when
        CircuitBreakerImpl<Integer> cb = builder.build();

        // then
        verifyDefaultsForEverything(cb);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void builder_constructor_with_strategy_results_in_expected_circuit_breaker_values(boolean useFactoryMethod) {
        // given
        BreakingEventStrategy<Integer> besMock = mock(BreakingEventStrategy.class);
        CircuitBreakerImpl.Builder<Integer> builder = (useFactoryMethod)
                                                      ? CircuitBreakerImpl.newBuilder(besMock)
                                                      : new CircuitBreakerImpl.Builder<>(besMock);

        // when
        CircuitBreakerImpl<Integer> cb = builder.build();

        // then
        assertThat(cb.scheduler).isSameAs(cb.getDefaultScheduledExecutorService());
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(cb.getDefaultStateChangeNotificationExecutorService());
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(cb.FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED);
        assertThat(cb.resetTimeoutNanos).isEqualTo(Duration.ofSeconds(cb.DEFAULT_RESET_TIMEOUT_SECONDS).toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(Optional.empty());
        assertThat(cb.breakingEventStrategy).isSameAs(besMock);
        assertThat(cb.breakingExceptionStrategy).isSameAs(cb.DEFAULT_BREAKING_EXCEPTION_STRATEGY);
        assertDefaultGeneratedCircuitBreakerId(cb);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void builder_honors_values_set(boolean useFactoryMethod) {
        // given
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ExecutorService scne = mock(ExecutorService.class);
        Integer maxFailures = 42;
        Duration resetTimeout = Duration.ofSeconds(42);
        Duration callTimeout = Duration.ofSeconds(4242);
        String id = UUID.randomUUID().toString();
        BreakingEventStrategy<Integer> eventStrategy = mock(BreakingEventStrategy.class);
        BreakingExceptionStrategy errorStrategy = mock(BreakingExceptionStrategy.class);
        CircuitBreakerImpl.Builder<Integer> builder = (useFactoryMethod)
                                                      ? CircuitBreakerImpl.newBuilder()
                                                      : new CircuitBreakerImpl.Builder<>();

        // when
        CircuitBreakerImpl<Integer> cb = builder
            .withScheduler(scheduler)
            .withStateChangeNotificationExecutor(scne)
            .withMaxConsecutiveFailuresAllowed(maxFailures)
            .withResetTimeout(resetTimeout)
            .withCallTimeout(callTimeout)
            .withId(id)
            .withBreakingEventStrategy(eventStrategy)
            .withBreakingExceptionStrategy(errorStrategy)
            .build();

        // then
        assertThat(cb.scheduler).isSameAs(scheduler);
        assertThat(cb.stateChangeNotificationExecutor).isSameAs(scne);
        assertThat(cb.maxConsecutiveFailuresAllowed).isSameAs(maxFailures);
        assertThat(cb.resetTimeoutNanos).isEqualTo(resetTimeout.toNanos());
        assertThat(cb.callTimeoutNanos).isEqualTo(Optional.of(callTimeout.toNanos()));
        assertThat(cb.id).isSameAs(id);
        assertThat(cb.breakingEventStrategy).isSameAs(eventStrategy);
        assertThat(cb.breakingExceptionStrategy).isSameAs(errorStrategy);
    }

    public static void assertDefaultGeneratedCircuitBreakerId(CircuitBreaker cb) {
        assertThat(cb.getId()).startsWith(DEFAULT_ID + "-");
        assertThat(cb.getId().length()).isEqualTo(DEFAULT_ID.length() + 37); // dash + UUID == 37 length
    }
}