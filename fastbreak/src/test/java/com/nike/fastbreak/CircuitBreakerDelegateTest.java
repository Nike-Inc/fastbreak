package com.nike.fastbreak;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link CircuitBreakerDelegate}
 *
 * @author Nic Munroe
 */
public class CircuitBreakerDelegateTest {

    private CircuitBreaker<String> delegateMock;
    private final Function<Integer, String> eventConverter = String::valueOf;
    private final Function<Integer, String> explodingEventConverter = theInt -> {
        throw new RuntimeException("kaboom");
    };
    private CircuitBreakerDelegate<Integer, String> wrapper;

    @Before
    public void beforeMethod() {
        delegateMock = mock(CircuitBreaker.class);
        wrapper = new CircuitBreakerDelegate<>(delegateMock, eventConverter);
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
    public void throwExceptionIfCircuitBreakerIsOpen_should_call_delegate_method() {
        // when
        wrapper.throwExceptionIfCircuitBreakerIsOpen();

        // then
        verify(delegateMock).throwExceptionIfCircuitBreakerIsOpen();
    }

    @Test
    public void handleEvent_should_use_eventConverter_and_then_call_delegate_method() {
        // given
        int event = 42;

        // when
        wrapper.handleEvent(event);

        // then
        verify(delegateMock).handleEvent(eventConverter.apply(event));
    }

    @Test
    public void handleEvent_should_not_explode_if_eventConverter_explodes() {
        // given
        CircuitBreakerDelegate<Integer, String> badCb =
            new CircuitBreakerDelegate<>(delegateMock, explodingEventConverter);

        // when
        Throwable cbExplosion = catchThrowable(() -> badCb.handleEvent(42));

        // then
        verify(delegateMock, times(0)).handleEvent(anyString());
        assertThat(cbExplosion).isNull();
    }

    @Test
    public void handleEvent_should_not_explode_if_delegate_explodes() {
        // given
        doThrow(new RuntimeException("splat")).when(delegateMock).handleEvent(anyString());

        // when
        Throwable cbExplosion = catchThrowable(() -> wrapper.handleEvent(42));

        // then
        verify(delegateMock).handleEvent(anyString());
        assertThat(cbExplosion).isNull();
    }

    @Test
    public void handleException_should_call_delegate_method() {
        // given
        Throwable error = mock(Throwable.class);

        // when
        wrapper.handleException(error);

        // then
        verify(delegateMock).handleException(error);
    }

    @Test
    public void handleException_should_not_explode_if_delegate_explodes() {
        // given
        doThrow(new RuntimeException("splat")).when(delegateMock).handleException(any());

        // when
        Throwable cbExplosion = catchThrowable(() -> wrapper.handleException(new Throwable()));

        // then
        verify(delegateMock).handleException(any());
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
}