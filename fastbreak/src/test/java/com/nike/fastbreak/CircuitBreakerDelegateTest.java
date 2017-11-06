package com.nike.fastbreak;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the functionality of {@link CircuitBreakerDelegate}
 *
 * @author Nic Munroe
 */
public class CircuitBreakerDelegateTest {

    private CircuitBreaker<String> delegateMock;
    private CircuitBreaker.ManualModeTask<String> delegateManualModeTaskMock;
    private final Function<Integer, String> eventConverter = String::valueOf;
    private CircuitBreakerDelegate<Integer, String> wrapper;
    private CircuitBreaker.ManualModeTask<Integer> wrapperManualModeTask;

    @Before
    public void beforeMethod() {
        delegateMock = mock(CircuitBreaker.class);
        delegateManualModeTaskMock = mock(CircuitBreaker.ManualModeTask.class);
        doReturn(delegateManualModeTaskMock).when(delegateMock).newManualModeTask();
        wrapper = new CircuitBreakerDelegate<>(delegateMock, eventConverter);
        wrapperManualModeTask = wrapper.newManualModeTask();
    }

    @Test
    public void constructor_should_use_the_arguments_passed_in() {
        // when
        CircuitBreakerDelegate<Integer, String> wrapper = new CircuitBreakerDelegate<>(delegateMock, eventConverter);

        // then
        assertThat(wrapper.delegate).isSameAs(delegateMock);
        assertThat(wrapper.eventConverter).isSameAs(eventConverter);
    }

    @Test
    public void newManualModeTask_returns_instance_of_DelegateManualModeTask_with_correct_fields() {
        // when
        CircuitBreaker.ManualModeTask<Integer> manualTask = wrapper.newManualModeTask();

        // then
        assertThat(manualTask).isInstanceOf(CircuitBreakerDelegate.DelegateManualModeTask.class);
        CircuitBreakerDelegate.DelegateManualModeTask dmmt = (CircuitBreakerDelegate.DelegateManualModeTask)manualTask;
        assertThat(dmmt.delegate).isSameAs(delegateManualModeTaskMock);
        assertThat(dmmt.eventConverter).isSameAs(wrapper.eventConverter);
        assertThat(dmmt.originatingCircuitBreaker).isSameAs(wrapper);
        assertThat(dmmt.originatingCircuitBreaker()).isSameAs(wrapper);
    }

    @Test
    public void throwExceptionIfCircuitBreakerIsOpen_should_call_delegate_method() {
        // when
        wrapperManualModeTask.throwExceptionIfCircuitBreakerIsOpen();

        // then
        verify(delegateManualModeTaskMock).throwExceptionIfCircuitBreakerIsOpen();
    }

    @Test
    public void handleEvent_should_use_eventConverter_and_then_call_delegate_method() {
        // given
        int event = 42;

        // when
        wrapperManualModeTask.handleEvent(event);

        // then
        verify(delegateManualModeTaskMock).handleEvent(eventConverter.apply(event));
    }

    @Test
    public void handleEvent_should_not_explode_if_eventConverter_explodes() {
        // given
        final Set<Boolean> explosionHappenedContainer = new HashSet<>();
        Function<Integer, String> explodingEventConverter = theInt -> {
            explosionHappenedContainer.add(true);
            throw new RuntimeException("kaboom");
        };
        CircuitBreakerDelegate<Integer, String> badCb =
            new CircuitBreakerDelegate<>(delegateMock, explodingEventConverter);
        CircuitBreaker.ManualModeTask<Integer> badCbManualModeTask = badCb.newManualModeTask();

        // when
        Throwable cbExplosion = catchThrowable(() -> badCbManualModeTask.handleEvent(42));

        // then
        assertThat(explosionHappenedContainer).contains(true);
        verify(delegateManualModeTaskMock, times(0)).handleEvent(anyString());
        assertThat(cbExplosion).isNull();
    }

    @Test
    public void handleEvent_should_not_explode_if_delegate_explodes() {
        // given
        doThrow(new RuntimeException("splat")).when(delegateManualModeTaskMock).handleEvent(anyString());

        // when
        Throwable cbExplosion = catchThrowable(() -> wrapperManualModeTask.handleEvent(42));

        // then
        verify(delegateManualModeTaskMock).handleEvent(anyString());
        assertThat(cbExplosion).isNull();
    }

    @Test
    public void handleException_should_call_delegate_method() {
        // given
        Throwable error = mock(Throwable.class);

        // when
        wrapperManualModeTask.handleException(error);

        // then
        verify(delegateManualModeTaskMock).handleException(error);
    }

    @Test
    public void handleException_should_not_explode_if_delegate_explodes() {
        // given
        doThrow(new RuntimeException("splat")).when(delegateManualModeTaskMock).handleException(any());

        // when
        Throwable cbExplosion = catchThrowable(() -> wrapperManualModeTask.handleException(new Throwable()));

        // then
        verify(delegateManualModeTaskMock).handleException(any());
        assertThat(cbExplosion).isNull();
    }

    @Test
    public void executeAsyncCall_calls_delegate_with_future_that_provides_converted_event_and_returns_future_that_provides_original_event() {
        // given
        CircuitBreaker<String> delegateCbSpy = spy(new CircuitBreakerImpl<>());
        CircuitBreakerDelegate<Integer, String> wrapperCb = new CircuitBreakerDelegate<>(delegateCbSpy, eventConverter);

        int event = 42;
        Supplier<CompletableFuture<Integer>> wrapperFutureSupplier = () -> CompletableFuture.completedFuture(event);

        // when
        CompletableFuture<Integer> wrapperCallResult = wrapperCb.executeAsyncCall(wrapperFutureSupplier);

        // then
        ArgumentCaptor<Supplier> delegateCallArgumentCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(delegateCbSpy).executeAsyncCall(delegateCallArgumentCaptor.capture());
        Supplier<CompletableFuture<String>> delegateFutureSupplier = delegateCallArgumentCaptor.getValue();
        assertThat(wrapperCallResult.join()).isEqualTo(event);
        assertThat(delegateFutureSupplier.get().join()).isEqualTo(eventConverter.apply(event));
    }

    @Test
    public void executeBlockingCall_calls_delegate_with_callable_that_provides_converted_event_and_returns_the_original_event()
        throws Exception {
        // given
        CircuitBreaker<String> delegateCbSpy = spy(new CircuitBreakerImpl<>());
        CircuitBreakerDelegate<Integer, String> wrapperCb = new CircuitBreakerDelegate<>(delegateCbSpy, eventConverter);

        int event = 42;
        Callable<Integer> wrapperSupplier = () -> event;

        // when
        Integer wrapperCallResult = wrapperCb.executeBlockingCall(wrapperSupplier);

        // then
        ArgumentCaptor<Callable> delegateCallArgumentCaptor = ArgumentCaptor.forClass(Callable.class);
        verify(delegateCbSpy).executeBlockingCall(delegateCallArgumentCaptor.capture());
        Callable<String> delegateCallable = delegateCallArgumentCaptor.getValue();
        assertThat(wrapperCallResult).isEqualTo(event);
        assertThat(delegateCallable.call()).isEqualTo(eventConverter.apply(event));
    }

    @Test
    public void onClose_calls_delegate_and_returns_self() {
        // given
        Runnable listener = mock(Runnable.class);

        // when
        CircuitBreaker<Integer> result = wrapper.onClose(listener);

        // then
        verify(delegateMock).onClose(listener);
        assertThat(result).isSameAs(wrapper);
    }

    @Test
    public void onHalfOpen_calls_delegate_and_returns_self() {
        // given
        Runnable listener = mock(Runnable.class);

        // when
        CircuitBreaker<Integer> result = wrapper.onHalfOpen(listener);

        // then
        verify(delegateMock).onHalfOpen(listener);
        assertThat(result).isSameAs(wrapper);
    }

    @Test
    public void onOpen_calls_delegate_and_returns_self() {
        // given
        Runnable listener = mock(Runnable.class);

        // when
        CircuitBreaker<Integer> result = wrapper.onOpen(listener);

        // then
        verify(delegateMock).onOpen(listener);
        assertThat(result).isSameAs(wrapper);
    }

    @Test
    public void getId_calls_delegate() {
        // given
        String generatedId = UUID.randomUUID().toString();
        when(delegateMock.getId()).thenReturn(generatedId);

        // when
        String id = wrapper.getId();

        // then
        assertThat(id).isEqualTo(generatedId);
        verify(delegateMock).getId();
    }
}