package com.nike.fastbreak;

import com.nike.fastbreak.exception.CircuitBreakerOpenException;

import org.junit.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link CircuitBreaker.ManualModeTask}
 *
 * @author Nic Munroe
 */
public class CircuitBreakerTest {

    @Test
    public void default_handleEvent_with_eventConverter_method_uses_eventConverter_and_calls_regular_handleEvent_method() {
        // given
        CircuitBreaker.ManualModeTask<String> cbSpy = spy(generateDefaultManualModeTask());
        Function<Integer, String> eventConverter = String::valueOf;

        // when
        cbSpy.handleEvent(42, eventConverter);

        // then
        verify(cbSpy).handleEvent("42");
    }

    @Test
    public void default_handleEvent_with_eventConverter_method_does_not_explode_when_eventConverter_explodes() {
        // given
        CircuitBreaker.ManualModeTask<String> cbSpy = spy(generateDefaultManualModeTask());
        Function<Integer, String> eventConverter = theInt -> {
            throw new RuntimeException("kaboom");
        };

        // when
        Throwable cbExplosion = catchThrowable(() -> cbSpy.handleEvent(4242, eventConverter));

        // then
        verify(cbSpy, times(0)).handleEvent(anyString());
        assertThat(cbExplosion).isNull();
    }

    private CircuitBreaker.ManualModeTask<String> generateDefaultManualModeTask() {
        return new CircuitBreaker.ManualModeTask<String>() {
            @Override
            public void throwExceptionIfCircuitBreakerIsOpen() throws CircuitBreakerOpenException {

            }

            @Override
            public void handleEvent(String event) {

            }

            @Override
            public void handleException(Throwable throwable) {

            }

            @Override
            public CircuitBreaker<String> originatingCircuitBreaker() {
                return null;
            }
        };
    }

}