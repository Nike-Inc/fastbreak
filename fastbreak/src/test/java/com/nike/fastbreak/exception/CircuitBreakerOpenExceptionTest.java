package com.nike.fastbreak.exception;

import org.junit.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link CircuitBreakerOpenException}.
 *
 * @author Nic Munroe
 */
public class CircuitBreakerOpenExceptionTest {

    @Test
    public void constructor_with_circuitBreakerId_and_message_works_as_expected() {
        // given
        String cbId = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString();

        // when
        CircuitBreakerOpenException ex = new CircuitBreakerOpenException(cbId, message);

        // then
        assertThat(ex.circuitBreakerId).isEqualTo(cbId);
        assertThat(ex).hasMessage(message);
    }

    @Test
    public void constructor_with_circuitBreakerId_and_message_and_cause_works_as_expected() {
        // given
        String cbId = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString();
        Throwable cause = mock(Throwable.class);

        // when
        CircuitBreakerOpenException ex = new CircuitBreakerOpenException(cbId, message, cause);

        // then
        assertThat(ex.circuitBreakerId).isEqualTo(cbId);
        assertThat(ex)
            .hasMessage(message)
            .hasCause(cause);
    }

    @Test
    public void constructor_with_circuitBreakerId_and_cause_works_as_expected() {
        // given
        String cbId = UUID.randomUUID().toString();
        Throwable cause = mock(Throwable.class);

        // when
        CircuitBreakerOpenException ex = new CircuitBreakerOpenException(cbId, cause);

        // then
        assertThat(ex.circuitBreakerId).isEqualTo(cbId);
        assertThat(ex).hasCause(cause);
    }
}